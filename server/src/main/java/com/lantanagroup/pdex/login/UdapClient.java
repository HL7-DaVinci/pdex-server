package com.lantanagroup.pdex.login;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.util.Base64;
import com.nimbusds.jose.util.X509CertUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.oauth2.sdk.GrantType;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod;
import com.nimbusds.oauth2.sdk.auth.JWTAuthenticationClaimsSet;
import com.nimbusds.oauth2.sdk.auth.PrivateKeyJWT;
import com.nimbusds.oauth2.sdk.client.ClientMetadata;
import com.nimbusds.oauth2.sdk.client.ClientRegistrationRequest;
import com.nimbusds.oauth2.sdk.http.HTTPRequest;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.id.Audience;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.JWTID;
import com.nimbusds.oauth2.sdk.util.JSONObjectUtils;
import java.io.IOException;
import java.net.URI;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minidev.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;

/**
 * Reads and checks the signed UDAP metadata of a FHIR server, registers this app at each sign-in start, and signs
 * the two JWTs of a UDAP sign-in: the software statement of the registration and the client assertion of the
 * token call. The key and the certificate chain come from the bean udapCertificate of auth-udap. With no such
 * bean, the app offers no UDAP sign-in.
 */
@Component
public class UdapClient {

	private static final Logger log = LoggerFactory.getLogger(UdapClient.class);

	// UDAP permits at most 5 minutes from iat to exp for a software statement and for a client assertion.
	private static final long LIFETIME_SECONDS = 300;

	// The type number of uniformResourceIdentifier in the subject alternative names (RFC 5280 section 4.2.1.6).
	private static final int URI_NAME = 6;

	private static final List<String> ENDPOINT_NAMES =
			List.of("authorization_endpoint", "token_endpoint", "registration_endpoint");

	private static final String CLAIMS_REASON = "The iss of the signed UDAP metadata is not a URI of its certificate,"
			+ " sub is not iss, or exp is not in the future";

	private static final String CANNOT_SIGN = "The certificate of this app cannot sign";

	/** A failed check, or a certificate that cannot sign. The message is a fixed reason with no value of a document. */
	public static final class RefusedException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		RefusedException(String reason) {
			super(reason);
		}
	}

	/** The three endpoints of the signed metadata, as published. */
	public record Endpoints(String authorization, String token, String registration) {}

	/**
	 * The UDAP sign-in that a FHIR server offers: its signed endpoints, the client URI of this app, and the
	 * scopes_supported values of the plain metadata. The scope list is empty when the member is absent, empty, or
	 * not an array of strings.
	 */
	public record Offer(Endpoints endpoints, String clientUri, List<String> scopes) {}

	private final LoginClients clients;
	private final ObjectProvider<KeyStore.PrivateKeyEntry> certificate;
	private final String clientName;
	private final String contact;
	private final String logoUri;
	private final String configuredScope;

	public UdapClient(LoginClients clients, ObjectProvider<KeyStore.PrivateKeyEntry> certificate,
			@Value("${app.login.udap.client-name:}") String clientName,
			@Value("${app.login.udap.contact:}") String contact,
			@Value("${app.login.udap.logo-uri:}") String logoUri,
			@Value("${app.login.udap.scope:}") String configuredScope) {
		this.clients = clients;
		this.certificate = certificate;
		this.clientName = clientName;
		this.contact = contact;
		this.logoUri = logoUri;
		this.configuredScope = configuredScope;
	}

	/**
	 * The key and the certificate chain of auth-udap, leaf first. Empty when auth-udap is absent, or when
	 * app.auth.enabled or app.auth.udap.enabled is false.
	 */
	public Optional<KeyStore.PrivateKeyEntry> certificate() {
		return Optional.ofNullable(certificate.getIfAvailable());
	}

	/**
	 * Reads {discoveryBase}/.well-known/udap with no credentials and runs the checks of checkMetadata. The iss must
	 * equal the FHIR base URL of a listed or typed server. The own server has no such compare.
	 *
	 * @return empty when the app has no certificate, or when the server answers 404 or 204 or gives no answer
	 * @throws RefusedException with a fixed reason for another status than 200, a body that is not a JSON object,
	 *     a leaf certificate of this app that clientUri refuses, or a failed check
	 */
	public Optional<Offer> offer(LoginClients.Target target) {
		if (certificate().isEmpty()) {
			return Optional.empty();
		}
		HTTPRequest request = new HTTPRequest(HTTPRequest.Method.GET, LoginClients.wellKnown(target.discoveryBase(), "udap"));
		request.setAccept("application/json");
		HTTPResponse response;
		try {
			response = clients.send(target.kind(), request);
		} catch (IOException e) {
			return Optional.empty();
		}
		int status = response.getStatusCode();
		if (status == 404 || status == 204) {
			return Optional.empty();
		}
		if (status != 200) {
			throw new RefusedException("The UDAP metadata of the server could not be read");
		}
		JSONObject document = jsonObject(response);
		if (document == null) {
			throw new RefusedException("The UDAP metadata of the server is not a JSON object");
		}
		String clientUri = clientUri();
		String expectedIssuer = target.kind() == LoginClients.Kind.OWN ? null : target.fhirBase().toString();
		return Optional.of(new Offer(checkMetadata(document, expectedIssuer), clientUri, scopes(document)));
	}

	/**
	 * Registers this app with a signed software statement. The body has only software_statement and udap. The scope
	 * of the statement comes from registrationScope with the scope list of the offer. The returned scope is not
	 * compared with the scope of the statement, and an answer with no redirect_uris passes.
	 *
	 * @param scope the scope of the sign-in
	 * @return the client_id of the answer, which is the client id of this sign-in only
	 * @throws RefusedException with a fixed reason for a failed call, an error answer, an answer with no
	 *     client_id, an empty grant_types (a cancelled registration), redirect_uris without the redirect URI, or a
	 *     certificate that cannot sign
	 */
	public String register(LoginClients.Target target, Offer offer, URI redirectUri, String scope) {
		String registration = offer.endpoints().registration();
		URI endpoint = URI.create(registration);
		ClientMetadata metadata = new ClientMetadata();
		metadata.setCustomField("udap", "1");
		HTTPRequest request = new ClientRegistrationRequest(endpoint, metadata,
				softwareStatement(clientUri(), registration, redirectUri, registrationScope(offer.scopes(), scope)), null)
				.toHTTPRequest();
		HTTPResponse response;
		try {
			response = clients.send(target.kind(), request);
		} catch (IOException e) {
			// A cause message can hold the full request URL, so the log gives only the host, the port, and the class
			// of the cause.
			log.warn("The registration call to {}://{}{} failed: {}", endpoint.getScheme(), endpoint.getHost(),
					endpoint.getPort() < 0 ? "" : ":" + endpoint.getPort(),
					NestedExceptionUtils.getMostSpecificCause(e).getClass().getName());
			throw new RefusedException("The registration call failed");
		}
		JSONObject answer = jsonObject(response);
		if (response.getStatusCode() != 200 && response.getStatusCode() != 201) {
			Object error = answer == null ? null : answer.get("error");
			throw new RefusedException("The registration endpoint answered with an error"
					+ LoginController.errorCode(error instanceof String code ? code : null));
		}
		if (!(answer != null && answer.get("client_id") instanceof String clientId) || clientId.isBlank()) {
			throw new RefusedException("The registration answer has no client_id");
		}
		if (answer.get("grant_types") instanceof List<?> grants && grants.isEmpty()) {
			throw new RefusedException("The registration answer has an empty grant_types, so the registration is cancelled");
		}
		if (answer.containsKey("redirect_uris")
				&& !(answer.get("redirect_uris") instanceof List<?> uris && uris.contains(redirectUri.toString()))) {
			throw new RefusedException("The redirect_uris of the registration answer do not hold the redirect URI of"
					+ " this sign-in");
		}
		return clientId;
	}

	/**
	 * The scope of the software statement is app.login.udap.scope when it is not blank, else the scopesSupported
	 * values that do not start with system/ followed by each value of the sign-in scope that they do not hold, else
	 * (with no scopesSupported values) the sign-in scope.
	 */
	String registrationScope(List<String> scopesSupported, String signInScope) {
		if (!configuredScope.isBlank()) {
			return configuredScope.trim();
		}
		Set<String> values = new LinkedHashSet<>();
		if (scopesSupported != null) {
			scopesSupported.stream().filter(value -> !value.startsWith("system/")).forEach(values::add);
		}
		values.addAll(Arrays.asList(signInScope.trim().split("\\s+")));
		return String.join(" ", values);
	}

	/**
	 * The client URI is the iss and the sub of the software statement.
	 *
	 * @return the first URI subject alternative name of the leaf certificate
	 * @throws IllegalStateException when the app has no certificate
	 * @throws RefusedException for a leaf certificate outside its validity period or with no URI subject
	 *     alternative name
	 */
	public String clientUri() {
		X509Certificate leaf = (X509Certificate) entry().getCertificate();
		try {
			leaf.checkValidity();
		} catch (CertificateException e) {
			throw new RefusedException("The certificate of this app is outside its validity period");
		}
		String uri = firstUri(leaf);
		if (uri == null) {
			throw new RefusedException("The certificate of this app has no URI subject alternative name");
		}
		return uri;
	}

	/** @return the first URI subject alternative name, byte for byte as the certificate has it, or null */
	static String firstUri(X509Certificate leaf) {
		List<String> uris = uris(leaf);
		return uris.isEmpty() ? null : uris.get(0);
	}

	/**
	 * Runs the eight checks of the signed metadata in order, and the first check that fails stops the sign-in. No
	 * check follows the certificate chain to a trust anchor.
	 *
	 * @param document the JSON object of {fhirBase}/.well-known/udap
	 * @param expectedIssuer the FHIR base URL that iss must equal, or null for the own server. The own server reads
	 *     itself over loopback, and the origin of the app can differ from the base URL that auth-udap signs.
	 * @return the three signed endpoints
	 * @throws RefusedException with a fixed reason that names the failed check
	 */
	static Endpoints checkMetadata(JSONObject document, String expectedIssuer) {
		SignedJWT signed;
		try {
			signed = document.get("signed_metadata") instanceof String text ? SignedJWT.parse(text) : null;
		} catch (ParseException e) {
			signed = null;
		}
		List<Base64> x5c = signed == null ? null : signed.getHeader().getX509CertChain();
		if (signed == null || !JWSAlgorithm.RS256.equals(signed.getHeader().getAlgorithm()) || x5c == null
				|| x5c.isEmpty()) {
			throw new RefusedException("The UDAP metadata has no signed_metadata that is an RS256 JWS with an x5c header");
		}

		X509Certificate certificate = X509CertUtils.parse(x5c.get(0).decode());
		if (!verifies(signed, certificate)) {
			throw new RefusedException("The signature of the signed UDAP metadata does not verify with its x5c certificate");
		}

		try {
			certificate.checkValidity();
		} catch (CertificateException e) {
			throw new RefusedException("The x5c certificate of the signed UDAP metadata is outside its validity period");
		}

		JWTClaimsSet claims;
		try {
			claims = signed.getJWTClaimsSet();
		} catch (ParseException e) {
			throw new RefusedException(CLAIMS_REASON);
		}
		String iss = claims.getIssuer();
		Date exp = claims.getExpirationTime();
		if (iss == null || !uris(certificate).contains(iss) || !iss.equals(claims.getSubject()) || exp == null
				|| !exp.after(new Date())) {
			throw new RefusedException(CLAIMS_REASON);
		}

		if (expectedIssuer != null && !withoutTrailingSlash(iss).equals(withoutTrailingSlash(expectedIssuer))) {
			throw new RefusedException("The iss of the signed UDAP metadata is not the base URL of the FHIR server");
		}

		for (String name : ENDPOINT_NAMES) {
			Object plain = document.get(name);
			if (!(plain instanceof String) || !plain.equals(claims.getClaim(name))) {
				throw new RefusedException("An endpoint of the UDAP metadata differs from its signed value");
			}
		}

		Object grants = document.get("grant_types_supported");
		if (document.containsKey("grant_types_supported")
				&& !(grants instanceof List<?> list && list.contains("authorization_code"))) {
			throw new RefusedException("The grant_types_supported of the UDAP metadata does not hold authorization_code");
		}

		Endpoints endpoints = new Endpoints((String) claims.getClaim("authorization_endpoint"),
				(String) claims.getClaim("token_endpoint"), (String) claims.getClaim("registration_endpoint"));
		try {
			for (String endpoint : List.of(endpoints.authorization(), endpoints.token(), endpoints.registration())) {
				LoginClients.requireHttp(URI.create(endpoint));
			}
		} catch (IllegalArgumentException e) {
			throw new RefusedException("A signed endpoint of the UDAP metadata is not an https URL,"
					+ " or an http URL on localhost or a loopback address");
		}
		return endpoints;
	}

	/**
	 * The software statement of a registration. The header has the full certificate chain in x5c, leaf first.
	 *
	 * @param registrationEndpoint the signed registration_endpoint as published, which is the aud value
	 * @throws IllegalStateException when the app has no certificate
	 * @throws RefusedException when the certificate cannot sign, or when app.login.udap.logo-uri is not a valid URI
	 */
	SignedJWT softwareStatement(String clientUri, String registrationEndpoint, URI redirectUri, String scope) {
		KeyStore.PrivateKeyEntry entry = entry();
		ClientMetadata metadata = new ClientMetadata();
		metadata.setName(clientName);
		metadata.setRedirectionURIs(Set.of(redirectUri));
		metadata.setEmailContacts(List.of(contact));
		try {
			metadata.setLogoURI(URI.create(logoUri));
		} catch (IllegalArgumentException e) {
			throw new RefusedException("app.login.udap.logo-uri is not a valid URI");
		}
		metadata.setGrantTypes(Set.of(GrantType.AUTHORIZATION_CODE));
		metadata.setResponseTypes(Set.of(ResponseType.CODE));
		metadata.setTokenEndpointAuthMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT);
		metadata.setScope(Scope.parse(scope));
		Instant issuedAt = now();
		try {
			JWTClaimsSet claims = new JWTClaimsSet.Builder(JWTClaimsSet.parse(metadata.toJSONObject()))
					.issuer(clientUri)
					.subject(clientUri)
					.audience(registrationEndpoint)
					.issueTime(Date.from(issuedAt))
					.expirationTime(Date.from(issuedAt.plusSeconds(LIFETIME_SECONDS)))
					.jwtID(new JWTID().getValue())
					.build();
			SignedJWT statement = new SignedJWT(
					new JWSHeader.Builder(JWSAlgorithm.RS256).x509CertChain(x5c(entry)).build(), claims);
			statement.sign(new RSASSASigner(rsaKey(entry)));
			return statement;
		} catch (ParseException e) {
			throw new IllegalStateException("The client metadata is not a valid claims set");
		} catch (JOSEException e) {
			throw new RefusedException(CANNOT_SIGN);
		}
	}

	/**
	 * Makes one client assertion for each token call. The header has the full certificate chain in x5c, leaf first.
	 *
	 * @param tokenEndpoint the signed token_endpoint as published, which is the aud value
	 * @throws IllegalStateException when the app has no certificate
	 * @throws RefusedException when the certificate cannot sign
	 */
	public PrivateKeyJWT assertion(String clientId, String tokenEndpoint) {
		KeyStore.PrivateKeyEntry entry = entry();
		Instant issuedAt = now();
		try {
			return new PrivateKeyJWT(
					new JWTAuthenticationClaimsSet(new ClientID(clientId), List.of(new Audience(tokenEndpoint)),
							Date.from(issuedAt.plusSeconds(LIFETIME_SECONDS)), null, Date.from(issuedAt), new JWTID()),
					JWSAlgorithm.RS256, rsaKey(entry), null, x5c(entry), null, null);
		} catch (JOSEException e) {
			throw new RefusedException(CANNOT_SIGN);
		}
	}

	private static List<String> scopes(JSONObject document) {
		if (!(document.get("scopes_supported") instanceof List<?> values)
				|| !values.stream().allMatch(String.class::isInstance)) {
			return List.of();
		}
		return values.stream().map(String.class::cast).toList();
	}

	// A body that is not a JSON object gives null.
	private static JSONObject jsonObject(HTTPResponse response) {
		try {
			return JSONObjectUtils.parse(response.getBody());
		} catch (com.nimbusds.oauth2.sdk.ParseException e) {
			return null;
		}
	}

	private KeyStore.PrivateKeyEntry entry() {
		return certificate().orElseThrow(() -> new IllegalStateException("The app has no UDAP certificate"));
	}

	private static RSAPrivateKey rsaKey(KeyStore.PrivateKeyEntry entry) {
		if (entry.getPrivateKey() instanceof RSAPrivateKey key) {
			return key;
		}
		throw new RefusedException("The key of the certificate of this app is not an RSA key");
	}

	private static List<Base64> x5c(KeyStore.PrivateKeyEntry entry) {
		List<Base64> chain = new ArrayList<>();
		try {
			for (Certificate certificate : entry.getCertificateChain()) {
				chain.add(Base64.encode(certificate.getEncoded()));
			}
		} catch (CertificateEncodingException e) {
			throw new RefusedException(CANNOT_SIGN);
		}
		return chain;
	}

	// The certificate and the signature come from the document, so any failure of the check gives false.
	private static boolean verifies(SignedJWT signed, X509Certificate certificate) {
		try {
			return certificate != null && certificate.getPublicKey() instanceof RSAPublicKey key
					&& signed.verify(new RSASSAVerifier(key));
		} catch (JOSEException | RuntimeException e) {
			return false;
		}
	}

	private static List<String> uris(X509Certificate certificate) {
		try {
			Collection<List<?>> names = certificate.getSubjectAlternativeNames();
			return names == null ? List.of() : names.stream()
					.filter(name -> Integer.valueOf(URI_NAME).equals(name.get(0)))
					.map(name -> (String) name.get(1))
					.toList();
		} catch (CertificateParsingException e) {
			return List.of();
		}
	}

	private static String withoutTrailingSlash(String uri) {
		return uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
	}

	// JWT times have a resolution of one second.
	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.SECONDS);
	}
}
