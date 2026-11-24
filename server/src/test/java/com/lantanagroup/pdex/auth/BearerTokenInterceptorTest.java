package com.lantanagroup.pdex.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.annotation.Create;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.ResourceParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.FifoMemoryPagingProvider;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.RestfulServer;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletConfig;

/** Runs the interceptor on a plain RestfulServer, with issuers served by the JDK HTTP and HTTPS servers. */
class BearerTokenInterceptorTest {

	static final String AUDIENCE = "fhir-api";
	static final String BYPASS_HEADER = "X-Bypass-Auth";
	static final FhirContext FHIR = FhirContext.forR4Cached();
	static final Object NOT_CALLED = new Object();
	static final AtomicReference<Object> claimsSeen = new AtomicReference<>();
	static final List<RestfulServer> servers = new ArrayList<>();

	@TempDir
	static Path certificates;

	static HttpServer issuerServer;
	static HttpsServer tlsServer;
	static LocalIssuer issuer;
	static LocalIssuer tls;
	static ECKey ecKey;
	/** A different RSA key with the key ID of the RSA key of the issuer. */
	static RSAKey forgedKey;
	static RestfulServer server;
	static RestfulServer publicPatients;

	/** An issuer at {@code <origin>/<name>} with a discovery document and a JWKS. */
	static final class LocalIssuer {
		final String url;
		final RSAKey key;
		final AtomicReference<JWKSet> keys;
		final AtomicInteger discoveryStatus = new AtomicInteger(200);

		LocalIssuer(String name) throws JOSEException {
			this(issuerServer, "http://127.0.0.1:" + issuerServer.getAddress().getPort(), name);
		}

		LocalIssuer(HttpServer host, String origin, String name) throws JOSEException {
			url = origin + "/" + name;
			key = new RSAKeyGenerator(2048).keyID(name + "-rsa").generate();
			keys = new AtomicReference<>(new JWKSet(key));
			host.createContext("/" + name + "/.well-known/openid-configuration", exchange -> respond(
					exchange, discoveryStatus.get(), "{\"issuer\":\"" + url + "\",\"jwks_uri\":\"" + url + "/jwks\"}"));
			host.createContext("/" + name + "/jwks", exchange -> respond(exchange, 200, keys.get().toString()));
		}
	}

	public static class PatientProvider implements IResourceProvider {
		@Override
		public Class<Patient> getResourceType() {
			return Patient.class;
		}

		@Read
		public Patient read(@IdParam IdType id, RequestDetails details) {
			claimsSeen.set(details.getUserData().get(BearerTokenInterceptor.CLAIMS_KEY));
			return patient(id.getIdPart());
		}

		@Create
		public MethodOutcome create(@ResourceParam Patient patient) {
			return new MethodOutcome(new IdType("Patient", "3"), true);
		}

		@Search
		public List<Patient> search() {
			return List.of(patient("1"), patient("2"));
		}

		@Operation(name = "$ping", idempotent = true)
		public Parameters ping(@IdParam(optional = true) IdType id) {
			return new Parameters();
		}

		private static Patient patient(String id) {
			Patient patient = new Patient();
			patient.setId(id);
			return patient;
		}
	}

	public static class ServerOperations {
		@Operation(name = "$ping", idempotent = true)
		public Parameters ping() {
			return new Parameters();
		}
	}

	public static class LateOperations {
		@Operation(name = "$late", idempotent = true)
		public Parameters late() {
			return new Parameters();
		}
	}

	@BeforeAll
	static void start() throws Exception {
		issuerServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		issuerServer.start();
		issuer = new LocalIssuer("issuer");
		ecKey = new ECKeyGenerator(Curve.P_256).keyID("issuer-ec").generate();
		forgedKey = new RSAKeyGenerator(2048).keyID(issuer.key.getKeyID()).generate();
		issuer.keys.set(new JWKSet(List.of(issuer.key, ecKey)));
		server = serve(interceptor(issuer, List.of(), BYPASS_HEADER));
		publicPatients = serve(interceptor(issuer, List.of("Patient"), null));
		startTlsIssuer();
	}

	static void startTlsIssuer() throws Exception {
		tlsServer = TestCertificates.untrustedHttpsServer(certificates);
		tlsServer.start();
		tls = new LocalIssuer(tlsServer, "https://localhost:" + tlsServer.getAddress().getPort(), "tls");
	}

	@AfterAll
	static void stop() {
		servers.forEach(RestfulServer::destroy);
		issuerServer.stop(0);
		tlsServer.stop(0);
	}

	static IssuerMetadata metadata(String issuerUrl, boolean sslVerify) {
		AuthResourceProperties properties = new AuthResourceProperties();
		properties.setIssuer(issuerUrl);
		return new IssuerMetadata(properties, sslVerify);
	}

	static BearerTokenInterceptor interceptor(LocalIssuer from, List<String> publicEndpoints, String bypassHeader) {
		return new BearerTokenInterceptor(metadata(from.url, true), AUDIENCE, null, publicEndpoints, bypassHeader);
	}

	static RestfulServer serve(BearerTokenInterceptor interceptor) throws Exception {
		RestfulServer restfulServer = new RestfulServer(FHIR);
		restfulServer.registerProviders(new PatientProvider(), new ServerOperations());
		FifoMemoryPagingProvider paging = new FifoMemoryPagingProvider(10);
		paging.setDefaultPageSize(1);
		restfulServer.setPagingProvider(paging);
		restfulServer.setDefaultResponseEncoding(EncodingEnum.JSON);
		restfulServer.registerInterceptor(interceptor);
		restfulServer.init(new MockServletConfig());
		servers.add(restfulServer);
		return restfulServer;
	}

	static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	static JWTClaimsSet.Builder claims(String issuerUrl) {
		return new JWTClaimsSet.Builder()
				.issuer(issuerUrl)
				.audience(AUDIENCE)
				.subject("user-1")
				.expirationTime(secondsFromNow(300));
	}

	static Date secondsFromNow(long seconds) {
		return new Date(System.currentTimeMillis() + seconds * 1000);
	}

	static String bearer(JWK key, JWTClaimsSet.Builder claims) throws JOSEException {
		return bearer(key, null, claims);
	}

	static String bearer(JWK key, JOSEObjectType type, JWTClaimsSet.Builder claims) throws JOSEException {
		boolean rsa = key instanceof RSAKey;
		SignedJWT jwt = new SignedJWT(
				new JWSHeader.Builder(rsa ? JWSAlgorithm.RS256 : JWSAlgorithm.ES256)
						.keyID(key.getKeyID())
						.type(type)
						.build(),
				claims.build());
		jwt.sign(rsa ? new RSASSASigner(key.toRSAKey()) : new ECDSASigner(key.toECKey()));
		return "Bearer " + jwt.serialize();
	}

	static MockHttpServletRequest request(String method, String pathAndQuery) {
		int query = pathAndQuery.indexOf('?');
		String path = query < 0 ? pathAndQuery : pathAndQuery.substring(0, query);
		MockHttpServletRequest request = new MockHttpServletRequest(method, "/fhir" + path);
		request.setServletPath("/fhir");
		request.setPathInfo(path);
		if (query >= 0) {
			request.setQueryString(pathAndQuery.substring(query + 1));
		}
		return request;
	}

	static MockHttpServletResponse send(RestfulServer restfulServer, MockHttpServletRequest request) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		restfulServer.service(request, response);
		return response;
	}

	static MockHttpServletResponse get(RestfulServer restfulServer, String pathAndQuery, String authorization)
			throws Exception {
		MockHttpServletRequest request = request("GET", pathAndQuery);
		if (authorization != null) {
			request.addHeader("Authorization", authorization);
		}
		return send(restfulServer, request);
	}

	static MockHttpServletRequest withHeader(String method, String path, String header, String value) {
		MockHttpServletRequest request = request(method, path);
		request.addHeader(header, value);
		return request;
	}

	static void assertUnauthorized(MockHttpServletResponse response, String challenge, String text) throws Exception {
		assertEquals(401, response.getStatus());
		assertEquals(challenge, response.getHeader("WWW-Authenticate"));
		OperationOutcome outcome = FHIR.newJsonParser().parseResource(OperationOutcome.class, response.getContentAsString());
		assertEquals(OperationOutcome.IssueType.SECURITY, outcome.getIssueFirstRep().getCode());
		assertEquals(text, outcome.getIssueFirstRep().getDiagnostics());
	}

	static void assertNoToken(MockHttpServletResponse response) throws Exception {
		assertUnauthorized(response, "Bearer", "Bearer token required");
	}

	static void assertInvalidToken(MockHttpServletResponse response) throws Exception {
		assertUnauthorized(response, "Bearer error=\"invalid_token\"", "Invalid bearer token");
	}

	static void assertCannotCheck(MockHttpServletResponse response) throws Exception {
		assertEquals(503, response.getStatus());
		assertNull(response.getHeader("WWW-Authenticate"));
		OperationOutcome outcome = FHIR.newJsonParser().parseResource(OperationOutcome.class, response.getContentAsString());
		assertEquals("The server cannot check the token", outcome.getIssueFirstRep().getDiagnostics());
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = "Basic abc")
	void noTokenIsRejected(String authorization) throws Exception {
		assertNoToken(get(server, "/Patient/1", authorization));
	}

	static Stream<Named<String>> validTokens() throws JOSEException {
		String token = bearer(issuer.key, JOSEObjectType.JWT, claims(issuer.url));
		return Stream.of(
				Named.of("RS256 with typ JWT", token),
				Named.of("typ at+jwt", bearer(issuer.key, new JOSEObjectType("at+jwt"), claims(issuer.url))),
				Named.of("ES256", bearer(ecKey, claims(issuer.url))),
				Named.of("scheme written bearer", "bearer " + token.substring("Bearer ".length())));
	}

	@ParameterizedTest
	@MethodSource("validTokens")
	void validTokenIsAccepted(String authorization) throws Exception {
		claimsSeen.set(NOT_CALLED);
		assertEquals(200, get(server, "/Patient/1", authorization).getStatus());
		@SuppressWarnings("unchecked")
		Map<String, Object> claims = (Map<String, Object>) claimsSeen.get();
		assertEquals("user-1", claims.get("sub"));
		assertThrows(UnsupportedOperationException.class, () -> claims.put("sub", "someone-else"));
	}

	static Stream<Named<String>> invalidTokens() throws JOSEException {
		JWSObject nullExp = new JWSObject(
				new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(issuer.key.getKeyID()).build(),
				new Payload("{\"iss\":\"" + issuer.url + "\",\"aud\":\"" + AUDIENCE + "\",\"sub\":\"user-1\",\"exp\":null}"));
		nullExp.sign(new RSASSASigner(issuer.key));
		return Stream.of(
				Named.of("key ID of a served key, signature of a different key", bearer(forgedKey, claims(issuer.url))),
				Named.of("unknown key ID", bearer(new RSAKeyGenerator(2048).keyID("unknown").generate(), claims(issuer.url))),
				Named.of("another iss", bearer(issuer.key, claims("https://other.example"))),
				Named.of("expired", bearer(issuer.key, claims(issuer.url).expirationTime(secondsFromNow(-120)))),
				Named.of("another aud", bearer(issuer.key, claims(issuer.url).audience("other-api"))),
				Named.of("no exp", bearer(issuer.key, claims(issuer.url).expirationTime(null))),
				Named.of("exp null", "Bearer " + nullExp.serialize()),
				Named.of("not a JWT", "Bearer not-a-jwt"));
	}

	@ParameterizedTest
	@MethodSource("invalidTokens")
	void invalidTokenIsRejected(String authorization) throws Exception {
		assertInvalidToken(get(server, "/Patient/1", authorization));
	}

	@Test
	void capabilitiesAndWellKnownNeedNoToken() throws Exception {
		assertEquals(200, get(server, "/metadata", null).getStatus());
		assertNotEquals(401, get(server, "/.well-known/smart-configuration", null).getStatus());
		assertNoToken(send(server, request("POST", "/metadata")));
	}

	@Test
	void discoveryRunsAgainAfterAFailure() throws Exception {
		LocalIssuer flaky = new LocalIssuer("flaky");
		flaky.discoveryStatus.set(500);
		RestfulServer flakyServer = serve(interceptor(flaky, List.of(), null));
		String token = bearer(flaky.key, claims(flaky.url));
		assertCannotCheck(get(flakyServer, "/Patient/1", token));
		flaky.discoveryStatus.set(200);
		assertEquals(200, get(flakyServer, "/Patient/1", token).getStatus());
	}

	@ParameterizedTest(name = "ssl-verify {0} gives {1}")
	@CsvSource({"false, 200", "true, 503"})
	void sslVerifySwitchesTheCertificateCheck(boolean sslVerify, int status) throws Exception {
		RestfulServer restfulServer =
				serve(new BearerTokenInterceptor(metadata(tls.url, sslVerify), AUDIENCE, null, List.of(), null));
		assertEquals(status, get(restfulServer, "/Patient/1", bearer(tls.key, claims(tls.url))).getStatus());
	}

	@Test
	void publicTypeEntryPassesEachMethod() throws Exception {
		assertEquals(200, get(publicPatients, "/Patient/1", null).getStatus());
		MockHttpServletRequest create = request("POST", "/Patient");
		create.setContentType("application/fhir+json");
		create.setContent("{\"resourceType\":\"Patient\"}".getBytes(StandardCharsets.UTF_8));
		assertEquals(201, send(publicPatients, create).getStatus());
		assertNoToken(get(publicPatients, "/Observation/1", null));
	}

	@Test
	void operationEntriesMatchTheirLevelOnly() throws Exception {
		RestfulServer serverPing = serve(interceptor(issuer, List.of("$ping"), null));
		assertEquals(200, get(serverPing, "/$ping", null).getStatus());
		assertNoToken(get(serverPing, "/Patient/$ping", null));

		RestfulServer patientPing = serve(interceptor(issuer, List.of("Patient/$ping"), null));
		assertEquals(200, get(patientPing, "/Patient/$ping", null).getStatus());
		assertEquals(200, get(patientPing, "/Patient/1/$ping", null).getStatus());
		assertNoToken(get(patientPing, "/$ping", null));
		assertNoToken(get(patientPing, "/Patient/1", null));
	}

	static Stream<Arguments> invalidConfigurations() {
		return Stream.of(
				Arguments.of(null, List.of()),
				Arguments.of(" ", List.of()),
				Arguments.of(issuer.url, List.of("Patient/1")));
	}

	@ParameterizedTest(name = "issuer \"{0}\", public endpoints {1}")
	@MethodSource("invalidConfigurations")
	void invalidConfigurationStopsConstruction(String issuerUrl, List<String> publicEndpoints) {
		IssuerMetadata issuerMetadata = metadata(issuerUrl, true);
		assertThrows(
				IllegalArgumentException.class,
				() -> new BearerTokenInterceptor(issuerMetadata, AUDIENCE, null, publicEndpoints, null));
	}

	@Test
	void bypassHeaderSkipsTheCheck() throws Exception {
		claimsSeen.set(NOT_CALLED);
		assertEquals(200, send(server, withHeader("GET", "/Patient/1", BYPASS_HEADER, "false")).getStatus());
		assertNull(claimsSeen.get());
	}

	@Test
	void pagingRequestPassesWithNoToken() throws Exception {
		MockHttpServletResponse search = get(publicPatients, "/Patient", null);
		assertEquals(200, search.getStatus());
		String next = FHIR.newJsonParser()
				.parseResource(Bundle.class, search.getContentAsString())
				.getLink(Bundle.LINK_NEXT)
				.getUrl();
		assertEquals(200, get(publicPatients, "?" + URI.create(next).getRawQuery(), null).getStatus());
		// HAPI gives a _getpages request to its paging handler only when the path names no resource type.
		assertNoToken(get(server, "/Patient?_getpages=x", null));
	}

	@Test
	void operationRegisteredAfterInitIsNotAPagingRequest() throws Exception {
		RestfulServer lateServer = serve(interceptor(issuer, List.of(), null));
		lateServer.registerProvider(new LateOperations());
		assertEquals(200, get(lateServer, "/$late", bearer(issuer.key, claims(issuer.url))).getStatus());
		assertNoToken(get(lateServer, "/$late?_getpages=x", null));
	}
}
