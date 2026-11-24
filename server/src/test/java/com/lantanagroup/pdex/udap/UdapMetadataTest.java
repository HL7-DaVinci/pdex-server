package com.lantanagroup.pdex.udap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lantanagroup.pdex.auth.TestCertificates;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.util.X509CertChainUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UdapMetadataTest {

	static final String BASE_URL = "http://localhost:8080/fhir";
	static final String PASSWORD = "changeit";
	static final List<String> ALGORITHMS = List.of("RS256", "RS384", "ES256", "ES384");
	static final String CERTIFICATION = "http://certification.test/udap";
	static final Map<String, Object> ISSUER_DOCUMENT = Map.of(
			"authorization_endpoint", "https://as.test/authorize",
			"token_endpoint", "https://as.test/token",
			"registration_endpoint", "https://as.test/register",
			"scopes_supported", List.of("openid", "system/*.read"));
	static final Map<String, Object> UDAP_ISSUER_DOCUMENT = Map.ofEntries(
			Map.entry("udap_versions_supported", List.of("1")),
			Map.entry("udap_profiles_supported", List.of("udap_dcr", "udap_authn", "udap_authz", "udap_to")),
			Map.entry("udap_authorization_extensions_supported", List.of("hl7-b2b")),
			Map.entry("udap_authorization_extensions_required", List.of()),
			Map.entry("udap_certifications_supported", List.of()),
			Map.entry("grant_types_supported", List.of("authorization_code", "refresh_token", "client_credentials")),
			Map.entry("scopes_supported", List.of("openid", "udap", "system/*.read")),
			Map.entry("authorization_endpoint", "https://as.test/authorize"),
			Map.entry("token_endpoint", "https://as.test/token"),
			Map.entry("token_endpoint_auth_methods_supported", List.of("private_key_jwt")),
			Map.entry("token_endpoint_auth_signing_alg_values_supported", ALGORITHMS),
			Map.entry("registration_endpoint", "https://as.test/register"),
			Map.entry("registration_endpoint_jwt_signing_alg_values_supported", ALGORITHMS));
	static final Supplier<Map<String, Object>> DOCUMENT_NOT_READ = () -> {
		throw new AssertionError("The UDAP metadata of the issuer was read");
	};

	@TempDir
	static Path directory;

	static UdapCertificate certificate;

	@BeforeAll
	static void loadCertificate() throws Exception {
		certificate = UdapCertificate.load(
				TestCertificates.selfSigned(directory, "RSA", "uri:" + BASE_URL, PASSWORD), PASSWORD);
	}

	/** The lists that the generator writes. They set no endpoint and no scope list. */
	static UdapProperties properties() {
		UdapProperties properties = new UdapProperties();
		properties.setEnabled(true);
		properties.setProfilesSupported(List.of("udap_dcr", "udap_authn", "udap_authz", "udap_to"));
		properties.setGrantTypesSupported(List.of("authorization_code", "refresh_token", "client_credentials"));
		properties.setAuthorizationExtensionsSupported(List.of("hl7-b2b"));
		properties.setAuthorizationExtensionsRequired(List.of());
		properties.setCertificationsSupported(List.of());
		properties.setTokenEndpointAuthSigningAlgValuesSupported(ALGORITHMS);
		properties.setRegistrationEndpointJwtSigningAlgValuesSupported(ALGORITHMS);
		return properties;
	}

	static Map<String, Object> build(UdapProperties properties) {
		return new UdapMetadata(properties, BASE_URL, certificate, () -> ISSUER_DOCUMENT).build();
	}

	static Map<String, Object> build(UdapProperties properties, Map<String, Object> issuerDocument) {
		return new UdapMetadata(properties, BASE_URL, certificate, () -> issuerDocument).build();
	}

	static JWTClaimsSet claimsOf(Map<String, Object> metadata) throws ParseException {
		return SignedJWT.parse((String) metadata.get("signed_metadata")).getJWTClaimsSet();
	}

	@Test
	void publishedDocument() {
		UdapProperties properties = properties();
		properties.setCertificationsRequired(List.of(CERTIFICATION));
		Map<String, Object> expected = new LinkedHashMap<>();
		expected.put("udap_versions_supported", List.of("1"));
		expected.put("udap_profiles_supported", List.of("udap_dcr", "udap_authn", "udap_authz", "udap_to"));
		expected.put("udap_authorization_extensions_supported", List.of("hl7-b2b"));
		expected.put("udap_authorization_extensions_required", List.of());
		expected.put("udap_certifications_supported", List.of());
		expected.put("grant_types_supported", List.of("authorization_code", "refresh_token", "client_credentials"));
		expected.put("scopes_supported", ISSUER_DOCUMENT.get("scopes_supported"));
		expected.put("authorization_endpoint", ISSUER_DOCUMENT.get("authorization_endpoint"));
		expected.put("token_endpoint", ISSUER_DOCUMENT.get("token_endpoint"));
		expected.put("registration_endpoint", ISSUER_DOCUMENT.get("registration_endpoint"));
		expected.put("token_endpoint_auth_methods_supported", List.of("private_key_jwt"));
		expected.put("token_endpoint_auth_signing_alg_values_supported", ALGORITHMS);
		expected.put("registration_endpoint_jwt_signing_alg_values_supported", ALGORITHMS);

		Map<String, Object> metadata = new LinkedHashMap<>(build(properties));
		assertNotNull(metadata.remove("signed_metadata"));

		assertEquals(expected, metadata);
	}

	@Test
	void certificationsRequiredIsPresentWhenSupportedHasAnEntry() {
		UdapProperties properties = properties();
		properties.setCertificationsSupported(List.of(CERTIFICATION));
		properties.setCertificationsRequired(List.of(CERTIFICATION));

		Map<String, Object> metadata = build(properties);

		assertEquals(List.of(CERTIFICATION), metadata.get("udap_certifications_supported"));
		assertEquals(List.of(CERTIFICATION), metadata.get("udap_certifications_required"));
	}

	@Test
	void authorizationEndpointOnlyWithAuthorizationCode() throws Exception {
		UdapProperties properties = properties();
		properties.setGrantTypesSupported(List.of("client_credentials"));
		properties.setAuthorizationEndpoint("https://proxy.test/authorize");

		Map<String, Object> metadata = build(properties);

		assertFalse(metadata.containsKey("authorization_endpoint"));
		assertFalse(claimsOf(metadata).getClaims().containsKey("authorization_endpoint"));
	}

	@Test
	void propertiesReplaceIssuerDocumentValues() {
		UdapProperties tokenEndpointSet = properties();
		tokenEndpointSet.setTokenEndpoint("https://proxy.test/token");

		Map<String, Object> mixed = build(tokenEndpointSet);

		assertEquals("https://proxy.test/token", mixed.get("token_endpoint"));
		assertEquals(ISSUER_DOCUMENT.get("authorization_endpoint"), mixed.get("authorization_endpoint"));
		assertEquals(ISSUER_DOCUMENT.get("registration_endpoint"), mixed.get("registration_endpoint"));
		assertEquals(ISSUER_DOCUMENT.get("scopes_supported"), mixed.get("scopes_supported"));

		UdapProperties eachValueSet = properties();
		eachValueSet.setAuthorizationEndpoint("https://proxy.test/authorize");
		eachValueSet.setTokenEndpoint("https://proxy.test/token");
		eachValueSet.setRegistrationEndpoint("https://proxy.test/register");
		eachValueSet.setScopesSupported(List.of("system/*.rs"));

		Map<String, Object> fromProperties =
				new UdapMetadata(eachValueSet, BASE_URL, certificate, DOCUMENT_NOT_READ).build();

		assertEquals("https://proxy.test/authorize", fromProperties.get("authorization_endpoint"));
		assertEquals("https://proxy.test/token", fromProperties.get("token_endpoint"));
		assertEquals("https://proxy.test/register", fromProperties.get("registration_endpoint"));
		assertEquals(List.of("system/*.rs"), fromProperties.get("scopes_supported"));
	}

	@Test
	void issuerDocumentGivesEachListThatNoPropertySets() {
		UdapProperties properties = new UdapProperties();
		properties.setEnabled(true);

		Map<String, Object> metadata = new LinkedHashMap<>(build(properties, UDAP_ISSUER_DOCUMENT));
		assertNotNull(metadata.remove("signed_metadata"));

		assertEquals(UDAP_ISSUER_DOCUMENT, metadata);
	}

	@Test
	void emptyListInTheConfigurationReplacesTheIssuerList() {
		UdapProperties properties = new UdapProperties();
		properties.setEnabled(true);
		properties.setAuthorizationExtensionsSupported(List.of());

		Map<String, Object> metadata = build(properties, UDAP_ISSUER_DOCUMENT);

		assertEquals(List.of(), metadata.get("udap_authorization_extensions_supported"));
		assertFalse(metadata.containsKey("udap_authorization_extensions_required"));
	}

	@Test
	void documentWithNoUdapVersionGivesNoList() {
		Map<String, Object> discoveryDocument = new HashMap<>(ISSUER_DOCUMENT);
		discoveryDocument.put("grant_types_supported", List.of("authorization_code", "implicit", "password"));
		UdapProperties noGrantTypes = properties();
		noGrantTypes.setGrantTypesSupported(null);

		IllegalStateException failure =
				assertThrows(IllegalStateException.class, () -> build(noGrantTypes, discoveryDocument));
		assertTrue(failure.getMessage().contains("grant-types-supported"), failure.getMessage());

		Map<String, Object> metadata = build(properties(), discoveryDocument);

		for (String endpoint : List.of("authorization_endpoint", "token_endpoint", "registration_endpoint")) {
			assertEquals(discoveryDocument.get(endpoint), metadata.get(endpoint), endpoint);
		}
	}

	@Test
	void signedMetadata() throws Exception {
		Map<String, Object> metadata = build(properties());
		long now = System.currentTimeMillis() / 1000;
		SignedJWT jwt = SignedJWT.parse((String) metadata.get("signed_metadata"));
		List<String> chain = new ArrayList<>();
		for (X509Certificate entry : certificate.chain()) {
			chain.add(Base64.getEncoder().encodeToString(entry.getEncoded()));
		}

		assertEquals(JWSAlgorithm.RS256, jwt.getHeader().getAlgorithm());
		assertEquals(
				chain,
				jwt.getHeader().getX509CertChain().stream().map(Object::toString).toList());
		RSAPublicKey leafKey = (RSAPublicKey)
				X509CertChainUtils.parse(jwt.getHeader().getX509CertChain()).get(0).getPublicKey();
		assertTrue(jwt.verify(new RSASSAVerifier(leafKey)));

		JWTClaimsSet claims = jwt.getJWTClaimsSet();
		assertEquals(BASE_URL, claims.getIssuer());
		assertEquals(BASE_URL, claims.getSubject());
		assertTrue(Math.abs(claims.getIssueTime().getTime() / 1000 - now) < 60);
		assertEquals(86400, (claims.getExpirationTime().getTime() - claims.getIssueTime().getTime()) / 1000);
		for (String endpoint : List.of("authorization_endpoint", "token_endpoint", "registration_endpoint")) {
			assertNotNull(metadata.get(endpoint));
			assertEquals(metadata.get(endpoint), claims.getClaim(endpoint));
		}

		String otherJwtId = claimsOf(build(properties())).getJWTID();
		assertNotNull(claims.getJWTID());
		assertNotEquals(claims.getJWTID(), otherJwtId);
	}
}
