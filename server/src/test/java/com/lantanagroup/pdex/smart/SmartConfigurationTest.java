package com.lantanagroup.pdex.smart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SmartConfigurationTest {

	static final String ISSUER = "https://issuer.example";
	static final List<String> CAPABILITIES = List.of(
			"launch-standalone",
			"client-confidential-symmetric",
			"sso-openid-connect",
			"permission-offline",
			"permission-patient",
			"permission-user",
			"permission-v1",
			"permission-v2");

	static Map<String, Object> build(List<String> capabilities, Map<String, Object> issuerDocument) {
		return new SmartConfiguration(capabilities, () -> issuerDocument).build();
	}

	@Test
	void keepsClientCredentialsWhenTheIssuerHasPrivateKeyJwt() {
		Map<String, Object> issuerDocument = Map.of(
				"token_endpoint", ISSUER + "/token",
				"code_challenge_methods_supported", List.of("S256"),
				"grant_types_supported", List.of("authorization_code", "client_credentials"),
				"token_endpoint_auth_methods_supported", List.of("private_key_jwt"),
				"token_endpoint_auth_signing_alg_values_supported", List.of("RS384", "ES384"));

		Map<String, Object> configuration = build(List.of("client-confidential-asymmetric"), issuerDocument);

		assertEquals(List.of("authorization_code", "client_credentials"), configuration.get("grant_types_supported"));
		assertEquals(List.of("private_key_jwt"), configuration.get("token_endpoint_auth_methods_supported"));
		assertEquals(
				List.of("RS384", "ES384"), configuration.get("token_endpoint_auth_signing_alg_values_supported"));
	}

	@Test
	void buildsTheDocumentFromTheIssuerDocument() {
		Map<String, Object> issuerDocument = Map.ofEntries(
				Map.entry("issuer", ISSUER),
				Map.entry("jwks_uri", ISSUER + "/jwks"),
				Map.entry("authorization_endpoint", ISSUER + "/authorize"),
				Map.entry("token_endpoint", ISSUER + "/token"),
				Map.entry("registration_endpoint", ISSUER + "/register"),
				Map.entry("introspection_endpoint", ISSUER + "/introspect"),
				Map.entry("revocation_endpoint", ISSUER + "/revoke"),
				Map.entry(
						"grant_types_supported",
						List.of("authorization_code", "client_credentials", "refresh_token", "implicit", "password")),
				Map.entry(
						"token_endpoint_auth_methods_supported",
						List.of("udap_pki_jwt", "client_secret_basic", "client_secret_post")),
				Map.entry("code_challenge_methods_supported", List.of("plain", "S256")),
				Map.entry("scopes_supported", List.of("openid", "patient/*.read")),
				Map.entry("response_types_supported", List.of("code")),
				Map.entry("end_session_endpoint", ISSUER + "/logout"));
		Map<String, Object> expected = new LinkedHashMap<>();
		expected.put("issuer", ISSUER);
		expected.put("jwks_uri", ISSUER + "/jwks");
		expected.put("authorization_endpoint", ISSUER + "/authorize");
		expected.put("grant_types_supported", List.of("authorization_code"));
		expected.put("token_endpoint", ISSUER + "/token");
		expected.put("token_endpoint_auth_methods_supported", List.of("client_secret_basic", "client_secret_post"));
		expected.put("scopes_supported", List.of("openid", "patient/*.read"));
		expected.put("response_types_supported", List.of("code"));
		expected.put("introspection_endpoint", ISSUER + "/introspect");
		expected.put("revocation_endpoint", ISSUER + "/revoke");
		expected.put("capabilities", CAPABILITIES);
		expected.put("code_challenge_methods_supported", List.of("S256"));

		assertEquals(expected, build(CAPABILITIES, issuerDocument));
	}

	@Test
	void leavesOutWhatHasNoSource() {
		Map<String, Object> issuerDocument = Map.of(
				"issuer", ISSUER,
				"token_endpoint", ISSUER + "/token",
				"code_challenge_methods_supported", List.of("S256"),
				"token_endpoint_auth_methods_supported", List.of("udap_pki_jwt"));

		assertEquals(
				Map.of(
						"grant_types_supported", List.of("authorization_code"),
						"token_endpoint", ISSUER + "/token",
						"capabilities", List.of("client-public"),
						"code_challenge_methods_supported", List.of("S256")),
				build(List.of("client-public"), issuerDocument));
	}

	/** Each case changes one member of a valid issuer document. A null value removes the member. */
	static Stream<Arguments> missingRequiredValues() {
		return Stream.of(
				Arguments.of("token_endpoint", null, List.of("client-public")),
				Arguments.of("code_challenge_methods_supported", List.of("plain"), List.of("client-public")),
				Arguments.of("authorization_endpoint", null, List.of("launch-standalone")),
				Arguments.of("jwks_uri", null, List.of("sso-openid-connect")));
	}

	@ParameterizedTest(name = "{0} {1} with {2}")
	@MethodSource("missingRequiredValues")
	void missingRequiredValueIsRefused(String member, Object value, List<String> capabilities) {
		Map<String, Object> issuerDocument = new HashMap<>(Map.of(
				"issuer", ISSUER,
				"jwks_uri", ISSUER + "/jwks",
				"authorization_endpoint", ISSUER + "/authorize",
				"token_endpoint", ISSUER + "/token",
				"code_challenge_methods_supported", List.of("S256")));
		if (value == null) {
			issuerDocument.remove(member);
		} else {
			issuerDocument.put(member, value);
		}

		IllegalStateException failure =
				assertThrows(IllegalStateException.class, () -> build(capabilities, issuerDocument));
		assertTrue(failure.getMessage().contains(member), failure.getMessage());
	}
}
