package com.lantanagroup.pdex.login;

import com.lantanagroup.pdex.login.UdapClient.Endpoints;
import com.lantanagroup.pdex.login.UdapClient.RefusedException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.util.Base64;
import com.nimbusds.jose.util.X509CertUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minidev.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class UdapClientTest {

	private static final String AUTHORIZE = "https://as.test/authorize";
	private static final String TOKEN = "https://as.test/token";
	private static final String REGISTER = "https://as.test/register";
	private static final Endpoints ENDPOINTS = new Endpoints(AUTHORIZE, TOKEN, REGISTER);
	private static final String FHIR_SERVER = "https://fhir.test/fhir";

	private static final KeyStore.PrivateKeyEntry A = LoginTestKeys.create("uri:https://app.test/fhir");
	private static final KeyStore.PrivateKeyEntry B = LoginTestKeys.create("uri:https://ca.test");
	private static final KeyStore.PrivateKeyEntry FHIR_KEY = LoginTestKeys.create("uri:https://fhir.test/fhir/");
	private static final KeyStore.PrivateKeyEntry OTHER_KEY = LoginTestKeys.create("uri:https://other.test/fhir");

	private final UdapClient udap = new UdapClient(mock(LoginClients.class), clientCertificate(), "udap-test",
			"mailto:admin@localhost", "https://build.fhir.org/icon-fhir-16.png", "");

	@Test
	void acceptsSignedMetadataAndSignsTheStatementAndTheAssertion() throws Exception {
		// The signed iss has one trailing slash more than the server entry.
		assertEquals(ENDPOINTS, UdapClient.checkMetadata(
				metadata(FHIR_KEY, FHIR_KEY.getCertificate(), "https://fhir.test/fhir/"), FHIR_SERVER));
		assertEquals("https://app.test/fhir", udap.clientUri());

		SignedJWT statement = signedByTheClient(udap.softwareStatement("https://app.test/fhir", REGISTER,
				URI.create("http://localhost:5173/auth/callback"), "openid patient/*.rs"));
		JWTClaimsSet claims = statement.getJWTClaimsSet();
		assertEquals("https://app.test/fhir", claims.getIssuer());
		assertEquals("https://app.test/fhir", claims.getSubject());
		assertEquals(List.of(REGISTER), claims.getAudience());
		assertLivesFiveMinutes(claims);
		assertFalse(claims.getJWTID().isBlank());
		assertEquals("udap-test", claims.getStringClaim("client_name"));
		assertEquals(List.of("mailto:admin@localhost"), claims.getStringListClaim("contacts"));
		assertEquals("https://build.fhir.org/icon-fhir-16.png", claims.getStringClaim("logo_uri"));
		assertEquals(List.of("http://localhost:5173/auth/callback"), claims.getStringListClaim("redirect_uris"));
		assertEquals(List.of("authorization_code"), claims.getStringListClaim("grant_types"));
		assertEquals(List.of("code"), claims.getStringListClaim("response_types"));
		assertEquals("private_key_jwt", claims.getStringClaim("token_endpoint_auth_method"));
		assertEquals("openid patient/*.rs", claims.getStringClaim("scope"));

		JWTClaimsSet assertion = signedByTheClient(udap.assertion("client-7", TOKEN).getClientAssertion())
				.getJWTClaimsSet();
		assertEquals("client-7", assertion.getIssuer());
		assertEquals("client-7", assertion.getSubject());
		assertEquals(List.of(TOKEN), assertion.getAudience());
		assertLivesFiveMinutes(assertion);
		assertNotEquals(assertion.getJWTID(),
				udap.assertion("client-7", TOKEN).getClientAssertion().getJWTClaimsSet().getJWTID());

		List<String> supported = List.of("openid", "patient/*.rs", "system/*.rs");
		UdapClient configured = new UdapClient(mock(LoginClients.class), clientCertificate(), "udap-test",
				"mailto:admin@localhost", "https://build.fhir.org/icon-fhir-16.png", "openid user/*.rs");
		assertEquals("openid user/*.rs", configured.registrationScope(supported, "openid launch/patient"));
		assertEquals("openid patient/*.rs launch/patient", udap.registrationScope(supported, "openid launch/patient"));
		assertEquals("openid launch/patient", udap.registrationScope(null, "openid launch/patient"));
	}

	@Test
	void refusesMetadataWithABadSignature() throws Exception {
		JSONObject document = metadata(A, FHIR_KEY.getCertificate(), FHIR_SERVER);
		RefusedException e = assertThrows(RefusedException.class, () -> UdapClient.checkMetadata(document, FHIR_SERVER));
		assertTrue(e.getMessage().contains("signature"), e.getMessage());
	}

	@Test
	void refusesMetadataWhoseIssDoesNotFitTheServer() throws Exception {
		JSONObject document = metadata(OTHER_KEY, OTHER_KEY.getCertificate(), "https://other.test/fhir");
		RefusedException e = assertThrows(RefusedException.class, () -> UdapClient.checkMetadata(document, FHIR_SERVER));
		assertTrue(e.getMessage().contains("base URL"), e.getMessage());
		// The own server has no base URL to compare.
		assertEquals(ENDPOINTS, UdapClient.checkMetadata(document, null));
	}

	private static ObjectProvider<KeyStore.PrivateKeyEntry> clientCertificate() {
		KeyStore.PrivateKeyEntry entry = new KeyStore.PrivateKeyEntry(A.getPrivateKey(),
				new Certificate[] {A.getCertificate(), B.getCertificate()});
		return new StaticListableBeanFactory(Map.of("udapCertificate", entry))
				.getBeanProvider(KeyStore.PrivateKeyEntry.class);
	}

	/** UDAP metadata whose signed_metadata the key of signer signs, with the certificate in x5c. */
	private static JSONObject metadata(KeyStore.PrivateKeyEntry signer, Certificate certificate, String iss)
			throws Exception {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.issuer(iss)
				.subject(iss)
				.issueTime(Date.from(now))
				.expirationTime(Date.from(now.plusSeconds(60)))
				.jwtID(UUID.randomUUID().toString())
				.claim("authorization_endpoint", AUTHORIZE)
				.claim("token_endpoint", TOKEN)
				.claim("registration_endpoint", REGISTER)
				.build();
		SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
				.x509CertChain(List.of(Base64.encode(certificate.getEncoded())))
				.build(), claims);
		signed.sign(new RSASSASigner(signer.getPrivateKey()));
		JSONObject document = new JSONObject();
		document.put("authorization_endpoint", AUTHORIZE);
		document.put("token_endpoint", TOKEN);
		document.put("registration_endpoint", REGISTER);
		document.put("grant_types_supported", List.of("authorization_code"));
		document.put("signed_metadata", signed.serialize());
		return document;
	}

	/** Parses the serialized JWT and checks that the client key signs it, with the chain of the client in x5c. */
	private static SignedJWT signedByTheClient(SignedJWT jwt) throws Exception {
		SignedJWT parsed = SignedJWT.parse(jwt.serialize());
		assertEquals(JWSAlgorithm.RS256, parsed.getHeader().getAlgorithm());
		assertEquals(List.of(A.getCertificate(), B.getCertificate()), parsed.getHeader().getX509CertChain().stream()
				.map(certificate -> X509CertUtils.parse(certificate.decode()))
				.toList());
		assertTrue(parsed.verify(new RSASSAVerifier((RSAPublicKey) A.getCertificate().getPublicKey())));
		return parsed;
	}

	private static void assertLivesFiveMinutes(JWTClaimsSet claims) {
		assertEquals(300, ChronoUnit.SECONDS.between(claims.getIssueTime().toInstant(),
				claims.getExpirationTime().toInstant()));
	}
}
