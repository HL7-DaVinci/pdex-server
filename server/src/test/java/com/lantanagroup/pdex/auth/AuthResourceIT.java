package com.lantanagroup.pdex.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ca.uhn.fhir.jpa.starter.Application;
import com.nimbusds.jwt.JWTClaimsSet;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots the full server with one issuer whose keys TestIssuer serves. TestIssuer serves no discovery
 * document, so a valid token passes only when app.auth.issuer and app.auth.jwks-uri bind.
 */
@ActiveProfiles("test")
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		classes = {Application.class},
		properties = {
			"spring.datasource.url=jdbc:h2:mem:authresourceit",
			// Sets the FHIR version of the server project. The application-test.yaml of the starter has R4.
			"hapi.fhir.fhir_version=R4",
			"app.auth.enabled=true",
			"app.auth.public-endpoints[0]=Patient",
			"app.auth.bypass-header=X-Bypass-Auth",
			"spring.ai.mcp.server.enabled=false",
			"spring.main.allow-bean-definition-overriding=true",
			"management.health.elasticsearch.enabled=false",
			"spring.jpa.properties.hibernate.search.backend.directory.type=local-heap"
		})
class AuthResourceIT {

	private static final String AUDIENCE = "fhir-api";
	private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
	private static final TestIssuer ISSUER = new TestIssuer();

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void issuer(DynamicPropertyRegistry registry) {
		registry.add("app.auth.issuer", ISSUER::url);
		registry.add("app.auth.audience", () -> AUDIENCE);
		registry.add("app.auth.jwks-uri", () -> ISSUER.url() + "/jwks");
	}

	@AfterAll
	static void stopIssuer() {
		ISSUER.stop();
	}

	@Test
	void requestWithNoTokenIsRejected() throws Exception {
		HttpResponse<String> response = send(fhir("Observation"));
		assertEquals(401, response.statusCode(), response.body());
		assertEquals(Optional.of("Bearer"), response.headers().firstValue("WWW-Authenticate"));
	}

	@Test
	void validTokenIsAccepted() throws Exception {
		String token = ISSUER.sign(new JWTClaimsSet.Builder().audience(AUDIENCE).subject("it-user"));
		HttpResponse<String> withToken = send(fhir("Observation").header("Authorization", "Bearer " + token));
		assertEquals(200, withToken.statusCode(), withToken.body());
		HttpResponse<String> withBypassHeader = send(fhir("Observation").header("X-Bypass-Auth", "1"));
		assertEquals(200, withBypassHeader.statusCode(), withBypassHeader.body());
		HttpResponse<String> publicEndpoint = send(fhir("Patient"));
		assertEquals(200, publicEndpoint.statusCode(), publicEndpoint.body());
	}

	private HttpRequest.Builder fhir(String path) {
		return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/fhir/" + path));
	}

	private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
		return HTTP.send(request.build(), BodyHandlers.ofString());
	}
}
