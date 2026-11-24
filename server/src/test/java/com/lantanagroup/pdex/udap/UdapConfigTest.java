package com.lantanagroup.pdex.udap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lantanagroup.pdex.auth.AuthResourceProperties;
import com.lantanagroup.pdex.auth.IssuerMetadata;
import com.lantanagroup.pdex.auth.TestCertificates;
import com.lantanagroup.pdex.auth.WellKnownInterceptor;
import ca.uhn.fhir.rest.server.RestfulServer;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Runs the startup of the feature with no Spring context. A JDK HTTP server is the issuer. */
class UdapConfigTest {

	static final String BASE_URL = "http://localhost:8080/fhir";
	static final String PASSWORD = "changeit";
	static final String FETCH_PASSWORD = "udap-test";

	record Request(String method, String path, String contentType, String body) {}

	@TempDir
	Path directory;

	final MockEnvironment environment = new MockEnvironment().withProperty("hapi.fhir.server_address", BASE_URL);
	final RestfulServer restfulServer = new RestfulServer();
	final List<Request> fetchRequests = new CopyOnWriteArrayList<>();

	HttpServer issuerServer;
	/** The body of a fetch response with status 200. With null, the issuer answers with status 500. */
	byte[] issuedCertificate;

	@BeforeEach
	void startIssuer() throws IOException {
		issuerServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		issuerServer.createContext("/", exchange -> {
			fetchRequests.add(new Request(
					exchange.getRequestMethod(),
					exchange.getRequestURI().getPath(),
					exchange.getRequestHeaders().getFirst("Content-Type"),
					new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
			byte[] body = issuedCertificate == null ? "failed".getBytes(StandardCharsets.UTF_8) : issuedCertificate;
			exchange.sendResponseHeaders(issuedCertificate == null ? 500 : 200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		issuerServer.start();
	}

	@AfterEach
	void stopIssuer() {
		issuerServer.stop(0);
	}

	AuthResourceProperties authProperties() {
		AuthResourceProperties properties = new AuthResourceProperties();
		properties.setEnabled(true);
		properties.setIssuer("http://127.0.0.1:" + issuerServer.getAddress().getPort() + "/issuer/");
		return properties;
	}

	static UdapProperties properties() {
		UdapProperties properties = new UdapProperties();
		properties.setEnabled(true);
		properties.setFetchCert(true);
		properties.setGrantTypesSupported(List.of("authorization_code", "client_credentials"));
		properties.setFetchCertRetryAttempts(1);
		properties.setFetchCertRetryDelay(0);
		return properties;
	}

	UdapConfig config(UdapProperties properties, AuthResourceProperties authProperties) {
		UdapConfig config = new UdapConfig(
				restfulServer, properties, authProperties, new IssuerMetadata(authProperties, true), environment);
		config.savedCertificateDirectory = directory;
		return config;
	}

	/** Writes a keystore to its own folder, because each keystore of TestCertificates has the same file name. */
	Path keystore(String folder, String keyAlgorithm, String password) throws Exception {
		return TestCertificates.selfSigned(
				Files.createDirectory(directory.resolve(folder)), keyAlgorithm, "uri:" + BASE_URL, password);
	}

	List<Object> udapInterceptors() {
		return restfulServer.getInterceptorService().getAllRegisteredInterceptors().stream()
				.filter(WellKnownInterceptor.class::isInstance)
				.toList();
	}

	IllegalStateException assertStops(UdapProperties properties) {
		UdapConfig config = config(properties, authProperties());
		IllegalStateException failure = assertThrows(IllegalStateException.class, config::register);
		assertTrue(udapInterceptors().isEmpty());
		return failure;
	}

	static void assertNames(IllegalStateException failure, String text) {
		assertTrue(failure.getMessage().contains(text), failure.getMessage());
	}

	@ParameterizedTest(name = "app.auth.enabled {0}, app.auth.udap.enabled {1}")
	@CsvSource({"true, false", "false, true"})
	void disabledRegistersNothing(boolean authEnabled, boolean udapEnabled) {
		AuthResourceProperties authProperties = authProperties();
		authProperties.setEnabled(authEnabled);
		UdapProperties properties = properties();
		properties.setEnabled(udapEnabled);

		config(properties, authProperties).register();

		assertTrue(udapInterceptors().isEmpty());
		assertTrue(fetchRequests.isEmpty());
	}

	@Test
	void startupStops() throws Exception {
		UdapProperties noSource = properties();
		noSource.setFetchCert(false);

		IllegalStateException noSourceFailure = assertStops(noSource);

		assertNames(noSourceFailure, "app.auth.udap.cert-file");
		assertNames(noSourceFailure, "app.auth.udap.fetch-cert");

		UdapProperties ellipticCurveKey = properties();
		ellipticCurveKey.setCertFile(keystore("ec", "EC", PASSWORD).toString());
		ellipticCurveKey.setCertPassword(PASSWORD);

		assertNames(assertStops(ellipticCurveKey), ellipticCurveKey.getCertFile());
	}

	@Test
	void fetchedCertificateIsSavedAndUsedAgain() throws Exception {
		issuedCertificate = Files.readAllBytes(keystore("issued", "RSA", FETCH_PASSWORD));

		config(properties(), authProperties()).register();

		String fetchBody = "{\"altNames\":[\"" + BASE_URL + "\"],\"password\":\"" + FETCH_PASSWORD + "\"}";
		assertEquals(
				List.of(new Request("POST", "/issuer/api/cert/generate", "application/json", fetchBody)), fetchRequests);
		assertArrayEquals(issuedCertificate, Files.readAllBytes(directory.resolve(UdapConfig.SAVED_CERTIFICATE)));
		assertEquals(1, udapInterceptors().size());

		config(properties(), authProperties()).register();

		assertEquals(1, fetchRequests.size());
		assertEquals(2, udapInterceptors().size());
	}

	@ParameterizedTest(name = "base URL {0} gives a problem: {1}")
	@CsvSource({"http://other.test/fhir, true", BASE_URL + ", false"})
	void certificateProblemComparesTheAlternativeNameWithTheBaseUrl(String baseUrl, boolean problem) throws Exception {
		X509Certificate leaf = UdapCertificate.load(keystore("leaf", "RSA", PASSWORD), PASSWORD).chain().get(0);

		assertEquals(problem, UdapConfig.certificateProblem(leaf, baseUrl) != null);
	}

	static Stream<Arguments> documentsWithNoValue() {
		Supplier<Map<String, Object>> readFails = () -> {
			throw new IllegalStateException("Could not read the UDAP metadata of the issuer");
		};
		Supplier<Map<String, Object>> noTokenEndpoint = () -> Map.of(
				"authorization_endpoint", "https://as.test/authorize",
				"registration_endpoint", "https://as.test/register",
				"scopes_supported", List.of("openid"));
		return Stream.of(
				Arguments.of("the issuer document does not load", readFails),
				Arguments.of("the issuer document has no token_endpoint", noTokenEndpoint));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("documentsWithNoValue")
	void metadataWithNoSourceGives503(String description, Supplier<Map<String, Object>> issuerDocument)
			throws Exception {
		UdapCertificate certificate = UdapCertificate.load(keystore("configured", "RSA", PASSWORD), PASSWORD);
		UdapMetadata metadata = new UdapMetadata(properties(), BASE_URL, certificate, issuerDocument);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/fhir/.well-known/udap");
		request.setServletPath("/fhir");
		request.setPathInfo("/.well-known/udap");
		MockHttpServletResponse response = new MockHttpServletResponse();

		assertFalse(new WellKnownInterceptor("/.well-known/udap", metadata::build).serve(request, response));
		assertEquals(503, response.getStatus());
	}

	@Test
	void emptyYamlListBindsToAnEmptyList() throws IOException {
		String yaml = "app:\n  auth:\n    udap:\n      certifications-supported: []\n";
		var sources = new YamlPropertySourceLoader()
				.load("application.yaml", new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));

		UdapProperties properties = new Binder(ConfigurationPropertySources.from(sources))
				.bind("app.auth.udap", UdapProperties.class)
				.get();

		assertEquals(List.of(), properties.getCertificationsSupported());
		assertNull(properties.getCertificationsRequired());
	}
}
