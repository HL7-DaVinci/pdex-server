package com.lantanagroup.pdex.smart;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ca.uhn.fhir.jpa.starter.Application;
import com.lantanagroup.pdex.auth.TestIssuer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots the full server with the bearer token check and the SMART configuration on. TestIssuer is the issuer
 * and serves its OpenID discovery document.
 */
@ActiveProfiles("test")
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		classes = {Application.class},
		properties = {
			"spring.datasource.url=jdbc:h2:mem:authsmartit",
			// Sets the FHIR version of the server project. The application-test.yaml of the starter has R4.
			"hapi.fhir.fhir_version=R4",
			"app.auth.enabled=true",
			"app.auth.smart.enabled=true",
			"app.auth.smart.capabilities=launch-standalone,sso-openid-connect",
			"spring.ai.mcp.server.enabled=false",
			"spring.main.allow-bean-definition-overriding=true",
			"management.health.elasticsearch.enabled=false",
			"spring.jpa.properties.hibernate.search.backend.directory.type=local-heap"
		})
class AuthSmartIT {

	static final ObjectMapper JSON = new ObjectMapper();
	static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
	static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
	static final Map<String, Object> ISSUER_DOCUMENT = Map.of(
			"issuer", "https://as.test",
			"jwks_uri", "https://as.test/jwks",
			"authorization_endpoint", "https://as.test/authorize",
			"token_endpoint", "https://as.test/token",
			"code_challenge_methods_supported", List.of("plain", "S256"));
	static final TestIssuer ISSUER = new TestIssuer().serve("/.well-known/openid-configuration", () -> ISSUER_DOCUMENT);

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void issuer(DynamicPropertyRegistry registry) {
		registry.add("app.auth.issuer", ISSUER::url);
	}

	@AfterAll
	static void stopIssuer() {
		ISSUER.stop();
	}

	@Test
	void smartConfigurationIsPublicAndJson() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(
						URI.create("http://localhost:" + port + "/fhir/.well-known/smart-configuration"))
				.header("Accept", "application/fhir+json")
				.GET()
				.build();

		HttpResponse<String> response = HTTP.send(request, BodyHandlers.ofString());

		assertEquals(200, response.statusCode(), response.body());
		assertEquals(Optional.of("application/json"), response.headers().firstValue("Content-Type"));
		Map<String, Object> configuration = JSON.readValue(response.body(), MAP);
		assertEquals("https://as.test/token", configuration.get("token_endpoint"));
		assertEquals(List.of("launch-standalone", "sso-openid-connect"), configuration.get("capabilities"));
		assertEquals(List.of("S256"), configuration.get("code_challenge_methods_supported"));
	}
}
