package com.lantanagroup.pdex.udap;

import com.lantanagroup.pdex.auth.IssuerMetadata;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.util.Base64;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;

/**
 * Builds the UDAP metadata of GET {baseURL}/.well-known/udap with its signed metadata. A set endpoint and a scope list
 * with an entry replace the value from the UDAP metadata of the issuer. A UDAP list in the configuration replaces it
 * also when the list is empty. The issuer gives a UDAP list only when its document has udap_versions_supported. An
 * issuer can publish its OpenID discovery document at {issuer}/.well-known/udap, and the grant types of that document
 * are not UDAP values.
 */
public class UdapMetadata {

	private static final String AUTHORIZATION_CODE = "authorization_code";

	private static final long SIGNED_METADATA_LIFETIME_MILLIS = 24L * 60 * 60 * 1000;

	private final UdapProperties properties;

	private final String baseUrl;

	private final Supplier<Map<String, Object>> issuerDocument;

	private final JWSHeader header;

	private final RSASSASigner signer;

	public UdapMetadata(
			UdapProperties properties,
			String baseUrl,
			UdapCertificate certificate,
			Supplier<Map<String, Object>> issuerDocument) {
		this.properties = properties;
		this.baseUrl = baseUrl;
		this.issuerDocument = issuerDocument;
		this.header = new JWSHeader.Builder(JWSAlgorithm.RS256)
				.x509CertChain(encode(certificate.chain()))
				.build();
		this.signer = new RSASSASigner(certificate.key());
	}

	/**
	 * Returns the metadata elements with signed_metadata.
	 *
	 * @throws IllegalStateException when no source gives a token endpoint, a registration endpoint, a scope list with
	 *     an entry, or a UDAP list that is not a -required list, or when authorization_code is a grant type and no
	 *     source gives an authorization endpoint
	 */
	public Map<String, Object> build() {
		List<String> grantTypesSupported =
				requiredList(properties.getGrantTypesSupported(), "grant_types_supported", "grant-types-supported");
		boolean authorizationCode = grantTypesSupported.contains(AUTHORIZATION_CODE);
		String authorizationEndpoint = authorizationCode
				? endpoint(properties.getAuthorizationEndpoint(), "authorization_endpoint", "authorization-endpoint")
				: null;
		String tokenEndpoint = endpoint(properties.getTokenEndpoint(), "token_endpoint", "token-endpoint");
		String registrationEndpoint =
				endpoint(properties.getRegistrationEndpoint(), "registration_endpoint", "registration-endpoint");
		List<String> scopesSupported = properties.getScopesSupported();
		if (scopesSupported.isEmpty()) {
			scopesSupported = IssuerMetadata.stringList(issuerDocument.get(), "scopes_supported");
		}
		if (scopesSupported == null || scopesSupported.isEmpty()) {
			throw missing("scopes_supported", "scopes-supported");
		}
		List<String> authorizationExtensionsSupported = requiredList(
				properties.getAuthorizationExtensionsSupported(),
				"udap_authorization_extensions_supported",
				"authorization-extensions-supported");
		List<String> certificationsSupported = requiredList(
				properties.getCertificationsSupported(), "udap_certifications_supported", "certifications-supported");

		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("udap_versions_supported", List.of("1"));
		metadata.put(
				"udap_profiles_supported",
				requiredList(properties.getProfilesSupported(), "udap_profiles_supported", "profiles-supported"));
		metadata.put("udap_authorization_extensions_supported", authorizationExtensionsSupported);
		if (!authorizationExtensionsSupported.isEmpty()) {
			metadata.put(
					"udap_authorization_extensions_required",
					Objects.requireNonNullElse(
							list(properties.getAuthorizationExtensionsRequired(), "udap_authorization_extensions_required"),
							List.of()));
		}
		metadata.put("udap_certifications_supported", certificationsSupported);
		if (!certificationsSupported.isEmpty()) {
			metadata.put(
					"udap_certifications_required",
					Objects.requireNonNullElse(
							list(properties.getCertificationsRequired(), "udap_certifications_required"), List.of()));
		}
		metadata.put("grant_types_supported", grantTypesSupported);
		metadata.put("scopes_supported", scopesSupported);
		if (authorizationEndpoint != null) {
			metadata.put("authorization_endpoint", authorizationEndpoint);
		}
		metadata.put("token_endpoint", tokenEndpoint);
		metadata.put("registration_endpoint", registrationEndpoint);
		metadata.put("token_endpoint_auth_methods_supported", List.of("private_key_jwt"));
		metadata.put(
				"token_endpoint_auth_signing_alg_values_supported",
				requiredList(
						properties.getTokenEndpointAuthSigningAlgValuesSupported(),
						"token_endpoint_auth_signing_alg_values_supported",
						"token-endpoint-auth-signing-alg-values-supported"));
		metadata.put(
				"registration_endpoint_jwt_signing_alg_values_supported",
				requiredList(
						properties.getRegistrationEndpointJwtSigningAlgValuesSupported(),
						"registration_endpoint_jwt_signing_alg_values_supported",
						"registration-endpoint-jwt-signing-alg-values-supported"));
		metadata.put("signed_metadata", sign(authorizationEndpoint, tokenEndpoint, registrationEndpoint));
		return metadata;
	}

	private List<String> list(List<String> configured, String member) {
		if (configured != null) {
			return configured;
		}
		Map<String, Object> document = issuerDocument.get();
		return document.containsKey("udap_versions_supported") ? IssuerMetadata.stringList(document, member) : null;
	}

	private List<String> requiredList(List<String> configured, String member, String property) {
		List<String> list = list(configured, member);
		if (list == null) {
			throw new IllegalStateException("The UDAP metadata has no value for " + member + ". Set app.auth.udap."
					+ property + ", or use an issuer whose UDAP metadata has udap_versions_supported and " + member
					+ ".");
		}
		return list;
	}

	private String endpoint(String configured, String member, String property) {
		if (StringUtils.isNotBlank(configured)) {
			return configured;
		}
		String value = IssuerMetadata.string(issuerDocument.get(), member);
		if (value == null) {
			throw missing(member, property);
		}
		return value;
	}

	private String sign(String authorizationEndpoint, String tokenEndpoint, String registrationEndpoint) {
		Date now = new Date();
		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
				.issuer(baseUrl)
				.subject(baseUrl)
				.issueTime(now)
				.expirationTime(new Date(now.getTime() + SIGNED_METADATA_LIFETIME_MILLIS))
				.jwtID(UUID.randomUUID().toString());
		if (authorizationEndpoint != null) {
			claims.claim("authorization_endpoint", authorizationEndpoint);
		}
		claims.claim("token_endpoint", tokenEndpoint).claim("registration_endpoint", registrationEndpoint);
		SignedJWT jwt = new SignedJWT(header, claims.build());
		try {
			jwt.sign(signer);
		} catch (JOSEException e) {
			throw new IllegalStateException("Could not sign the UDAP metadata", e);
		}
		return jwt.serialize();
	}

	private static IllegalStateException missing(String element, String property) {
		return new IllegalStateException("The UDAP metadata has no value for " + element + ". Set app.auth.udap."
				+ property + ", or use an issuer whose UDAP metadata has " + element + ".");
	}

	private static List<Base64> encode(List<X509Certificate> chain) {
		List<Base64> x5c = new ArrayList<>();
		for (X509Certificate certificate : chain) {
			try {
				x5c.add(Base64.encode(certificate.getEncoded()));
			} catch (CertificateEncodingException e) {
				throw new IllegalStateException("Could not encode the certificate chain", e);
			}
		}
		return x5c;
	}
}
