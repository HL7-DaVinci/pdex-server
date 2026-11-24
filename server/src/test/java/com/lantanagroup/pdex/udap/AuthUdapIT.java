package com.lantanagroup.pdex.udap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.jpa.starter.Application;
import com.lantanagroup.pdex.auth.TestCertificates;
import com.lantanagroup.pdex.auth.TestIssuer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots the full server with the bearer token check and the UDAP metadata on. TestIssuer is the issuer and
 * serves its UDAP metadata, which gives each endpoint and the scope list. The certificate is a test file, so the
 * server fetches no certificate.
 */
@ActiveProfiles("test")
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		classes = {Application.class},
		properties = {
			"spring.datasource.url=jdbc:h2:mem:authudapit",
			// Sets the FHIR version of the server project. The application-test.yaml of the starter has R4.
			"hapi.fhir.fhir_version=R4",
			"hapi.fhir.server_address=" + AuthUdapIT.BASE_URL,
			"app.auth.enabled=true",
			"app.auth.udap.enabled=true",
			"app.auth.udap.profiles-supported=udap_dcr,udap_authn,udap_authz,udap_to",
			"app.auth.udap.grant-types-supported=authorization_code,refresh_token,client_credentials",
			"app.auth.udap.authorization-extensions-supported=hl7-b2b",
			"app.auth.udap.authorization-extensions-required=",
			"app.auth.udap.certifications-supported=",
			"app.auth.udap.token-endpoint-auth-signing-alg-values-supported=RS256,RS384,ES256,ES384",
			"app.auth.udap.registration-endpoint-jwt-signing-alg-values-supported=RS256,RS384,ES256,ES384",
			"spring.ai.mcp.server.enabled=false",
			"spring.main.allow-bean-definition-overriding=true",
			"management.health.elasticsearch.enabled=false",
			"spring.jpa.properties.hibernate.search.backend.directory.type=local-heap"
		})
class AuthUdapIT {

	static final String BASE_URL = "http://localhost:8080/fhir";
	static final String PASSWORD = "changeit";
	static final ObjectMapper JSON = new ObjectMapper();
	static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
	static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
	static final Map<String, Object> ISSUER_UDAP = Map.of(
			"authorization_endpoint", "https://as.test/authorize",
			"token_endpoint", "https://as.test/token",
			"registration_endpoint", "https://as.test/register",
			"scopes_supported", List.of("openid", "system/*.rs"));
	static final AtomicInteger ISSUER_UDAP_READS = new AtomicInteger();
	static final TestIssuer ISSUER = new TestIssuer().serve("/.well-known/udap", () -> {
		ISSUER_UDAP_READS.incrementAndGet();
		return ISSUER_UDAP;
	});

	@TempDir
	static Path directory;

	@LocalServerPort
	private int port;

	@Autowired
	private ObjectProvider<KeyStore.PrivateKeyEntry> udapCertificate;

	@DynamicPropertySource
	static void issuerAndCertificate(DynamicPropertyRegistry registry) throws Exception {
		registry.add("app.auth.issuer", ISSUER::url);
		Path keystore = TestCertificates.selfSigned(directory, "RSA", "uri:" + BASE_URL, PASSWORD);
		registry.add("app.auth.udap.cert-file", keystore::toString);
		registry.add("app.auth.udap.cert-password", () -> PASSWORD);
	}

	@AfterAll
	static void stopIssuer() {
		ISSUER.stop();
	}

	@Test
	void metadataNeedsNoToken() throws Exception {
		HttpResponse<String> response = send(udap("").GET());
		HttpResponse<String> community = send(udap("?community=x").GET());

		assertEquals(200, response.statusCode(), response.body());
		assertEquals(Optional.of("application/json"), response.headers().firstValue("Content-Type"));
		Map<String, Object> metadata = JSON.readValue(response.body(), MAP);
		SignedJWT jwt = SignedJWT.parse((String) metadata.get("signed_metadata"));
		assertEquals(BASE_URL, jwt.getJWTClaimsSet().getIssuer());
		for (Map.Entry<String, Object> value : ISSUER_UDAP.entrySet()) {
			assertEquals(value.getValue(), metadata.get(value.getKey()), value.getKey());
		}
		assertEquals(200, community.statusCode(), community.body());
		assertEquals(metadata.keySet(), JSON.readValue(community.body(), MAP).keySet());
		assertEquals(1, ISSUER_UDAP_READS.get());
		KeyStore.PrivateKeyEntry entry = udapCertificate.getIfAvailable();
		assertNotNull(entry);
		assertTrue(((X509Certificate) entry.getCertificate()).getSubjectAlternativeNames().contains(List.of(6, BASE_URL)));
	}

	private HttpRequest.Builder udap(String suffix) {
		return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/fhir/.well-known/udap" + suffix));
	}

	private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
		return HTTP.send(request.build(), BodyHandlers.ofString());
	}
}
