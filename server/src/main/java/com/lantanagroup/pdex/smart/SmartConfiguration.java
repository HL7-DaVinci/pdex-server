package com.lantanagroup.pdex.smart;

import com.lantanagroup.pdex.auth.IssuerMetadata;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Builds the SMART configuration of GET {baseURL}/.well-known/smart-configuration from the OpenID discovery document
 * of the issuer and the configured capabilities. The grant types and the client authentication methods keep only the
 * values that SMART App Launch lists. The PKCE methods lose plain. A member of the issuer document with a wrong JSON
 * type counts as absent.
 */
public class SmartConfiguration {

	private static final String NO_MEMBER = "The OpenID discovery document of the issuer has no ";
	private static final String AUTHORIZATION_CODE = "authorization_code";
	private static final String CLIENT_CREDENTIALS = "client_credentials";
	private static final String PRIVATE_KEY_JWT = "private_key_jwt";
	private static final List<String> LAUNCH_CAPABILITIES = List.of("launch-ehr", "launch-standalone");
	private static final List<String> TOKEN_ENDPOINT_AUTH_METHODS =
			List.of("client_secret_post", "client_secret_basic", PRIVATE_KEY_JWT);

	private final List<String> capabilities;

	private final Supplier<Map<String, Object>> issuerDocument;

	public SmartConfiguration(List<String> capabilities, Supplier<Map<String, Object>> issuerDocument) {
		this.capabilities = capabilities;
		this.issuerDocument = issuerDocument;
	}

	/**
	 * Returns the members of the SMART configuration.
	 *
	 * @throws IllegalStateException when the issuer document has no token_endpoint or no S256 in
	 *     code_challenge_methods_supported, when a launch capability has no authorization_endpoint, or when
	 *     sso-openid-connect has no issuer or no jwks_uri
	 */
	public Map<String, Object> build() {
		Map<String, Object> document = issuerDocument.get();
		boolean openIdConnect = capabilities.contains("sso-openid-connect");
		String issuer = IssuerMetadata.string(document, "issuer");
		String jwksUri = IssuerMetadata.string(document, "jwks_uri");
		String authorizationEndpoint = IssuerMetadata.string(document, "authorization_endpoint");
		String tokenEndpoint = IssuerMetadata.string(document, "token_endpoint");
		List<String> codeChallengeMethods = IssuerMetadata.stringList(document, "code_challenge_methods_supported");
		List<String> issuerAuthMethods = IssuerMetadata.stringList(document, "token_endpoint_auth_methods_supported");
		List<String> issuerGrantTypes = IssuerMetadata.stringList(document, "grant_types_supported");
		if (tokenEndpoint == null) {
			throw new IllegalStateException(NO_MEMBER + "token_endpoint");
		}
		if (codeChallengeMethods == null || !codeChallengeMethods.contains("S256")) {
			throw new IllegalStateException(NO_MEMBER + "S256 in code_challenge_methods_supported");
		}
		if (authorizationEndpoint == null && capabilities.stream().anyMatch(LAUNCH_CAPABILITIES::contains)) {
			throw new IllegalStateException(
					NO_MEMBER + "authorization_endpoint, and capabilities has launch-ehr or launch-standalone");
		}
		if (openIdConnect && issuer == null) {
			throw new IllegalStateException(NO_MEMBER + "issuer, and capabilities has sso-openid-connect");
		}
		if (openIdConnect && jwksUri == null) {
			throw new IllegalStateException(NO_MEMBER + "jwks_uri, and capabilities has sso-openid-connect");
		}

		Map<String, Object> configuration = new LinkedHashMap<>();
		if (openIdConnect) {
			configuration.put("issuer", issuer);
		}
		putIfPresent(configuration, "jwks_uri", jwksUri);
		putIfPresent(configuration, "authorization_endpoint", authorizationEndpoint);
		configuration.put("grant_types_supported", grantTypes(issuerGrantTypes, issuerAuthMethods));
		configuration.put("token_endpoint", tokenEndpoint);
		if (issuerAuthMethods != null) {
			List<String> authMethods =
					issuerAuthMethods.stream().filter(TOKEN_ENDPOINT_AUTH_METHODS::contains).toList();
			if (!authMethods.isEmpty()) {
				configuration.put("token_endpoint_auth_methods_supported", authMethods);
			}
		}
		copyList(configuration, document, "token_endpoint_auth_signing_alg_values_supported");
		copyList(configuration, document, "scopes_supported");
		copyList(configuration, document, "response_types_supported");
		putIfPresent(configuration, "introspection_endpoint", IssuerMetadata.string(document, "introspection_endpoint"));
		putIfPresent(configuration, "revocation_endpoint", IssuerMetadata.string(document, "revocation_endpoint"));
		configuration.put("capabilities", capabilities);
		configuration.put(
				"code_challenge_methods_supported",
				codeChallengeMethods.stream().filter(method -> !"plain".equals(method)).toList());
		return configuration;
	}

	/**
	 * Keeps authorization_code and client_credentials of the issuer list. client_credentials stays only when the
	 * issuer has private_key_jwt, the client authentication of SMART Backend Services. With no issuer list, the
	 * result is authorization_code, because the OpenID default is authorization_code and implicit and SMART has only
	 * the first.
	 */
	private static List<String> grantTypes(List<String> issuerGrantTypes, List<String> issuerAuthMethods) {
		if (issuerGrantTypes == null) {
			return List.of(AUTHORIZATION_CODE);
		}
		boolean privateKeyJwt = issuerAuthMethods != null && issuerAuthMethods.contains(PRIVATE_KEY_JWT);
		return issuerGrantTypes.stream()
				.filter(type -> AUTHORIZATION_CODE.equals(type) || (privateKeyJwt && CLIENT_CREDENTIALS.equals(type)))
				.toList();
	}

	private static void copyList(Map<String, Object> configuration, Map<String, Object> document, String member) {
		putIfPresent(configuration, member, IssuerMetadata.stringList(document, member));
	}

	private static void putIfPresent(Map<String, Object> configuration, String member, Object value) {
		if (value != null) {
			configuration.put(member, value);
		}
	}
}
