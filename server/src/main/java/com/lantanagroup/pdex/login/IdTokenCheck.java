package com.lantanagroup.pdex.login;

import com.lantanagroup.pdex.login.LoginClients.Discovery;
import com.lantanagroup.pdex.login.LoginClients.PlainHttpException;
import com.lantanagroup.pdex.login.LoginClients.Target;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.http.HTTPRequest;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.oauth2.sdk.util.JSONObjectUtils;
import com.nimbusds.openid.connect.sdk.claims.IDTokenClaimsSet;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import java.util.regex.Pattern;
import net.minidev.json.JSONObject;
import org.springframework.stereotype.Component;

/**
 * Validates the ID token of a token response and gives its fhirUser claim. The key set comes from the
 * jwks_uri of the discovery document, else from the OpenID document of the issuer. The issuer of the token
 * itself is never a source. When the discovery document has no issuer, the claim is read with no check and is
 * for display only.
 */
@Component
public class IdTokenCheck {

	private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");

	public static final class RejectedException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		RejectedException(String reason) {
			super(reason);
		}
	}

	private final LoginClients clients;

	public IdTokenCheck(LoginClients clients) {
		this.clients = clients;
	}

	/**
	 * @return the fhirUser claim of a valid token, joined to the FHIR base when it is relative. Empty when the
	 *         token has no fhirUser claim. When the discovery document has no issuer, the claim comes from the
	 *         token with no signature, issuer, audience, or expiry check, and the value is for display only. Then a
	 *         token that does not parse, or a claim that is not a string, gives empty.
	 * @throws RejectedException with a fixed reason for a token that fails a check
	 */
	public Optional<String> fhirUser(Target target, Discovery discovery, String clientId, String idToken) {
		if (discovery.issuer() == null) {
			// OpenID Connect Core 3.1.3.7 item 6 permits the TLS check of the token call in place of the signature
			// check. Nothing in the app decides access from this value.
			return unchecked(idToken).map(value -> absolute(target, value));
		}
		SignedJWT jwt = signed(idToken);
		JWSAlgorithm alg = jwt.getHeader().getAlgorithm();
		// SMART requires the authorization server to support RS256, and the server can sign with any RSA or EC
		// algorithm. An HMAC key is a shared secret, and a token with alg none has no signature.
		if (!JWSAlgorithm.Family.RSA.contains(alg) && !JWSAlgorithm.Family.EC.contains(alg)) {
			throw new RejectedException("The ID token is not signed with an RSA or EC algorithm");
		}
		JWKSet keys = keySet(target, discovery);
		IDTokenClaimsSet claims;
		try {
			claims = new IDTokenValidator(new Issuer(discovery.issuer()), new ClientID(clientId), alg, keys).validate(jwt,
					null);
		} catch (Exception e) {
			throw new RejectedException("The ID token is not valid");
		}
		// The validator accepts a second audience when azp is the client id.
		if (claims.getAudience().size() != 1) {
			throw new RejectedException("The ID token has more than one audience");
		}
		return Optional.ofNullable(claims.getStringClaim("fhirUser")).map(value -> absolute(target, value));
	}

	// A relative value is a FHIR reference such as Patient/123, so it goes under the FHIR base.
	private static String absolute(Target target, String fhirUser) {
		return SCHEME.matcher(fhirUser).find() ? fhirUser : target.fhirBaseWithoutSlash() + "/" + fhirUser;
	}

	// An encrypted token that the server cannot decrypt has no claims set.
	private static Optional<String> unchecked(String idToken) {
		try {
			JWTClaimsSet claims = JWTParser.parse(idToken).getJWTClaimsSet();
			return claims == null ? Optional.empty() : Optional.ofNullable(claims.getStringClaim("fhirUser"));
		} catch (java.text.ParseException e) {
			return Optional.empty();
		}
	}

	private static SignedJWT signed(String idToken) {
		JWT jwt;
		try {
			jwt = JWTParser.parse(idToken);
		} catch (java.text.ParseException e) {
			throw new RejectedException("The ID token is not a valid JWT");
		}
		if (jwt instanceof SignedJWT signed) {
			return signed;
		}
		throw new RejectedException("The ID token is not signed with an RSA or EC algorithm");
	}

	private JWKSet keySet(Target target, Discovery discovery) {
		try {
			URI jwksUri = discovery.jwksUri() != null ? discovery.jwksUri() : jwksUriOfIssuer(target, discovery.issuer());
			return JWKSet.parse(read(target, jwksUri));
		} catch (RejectedException e) {
			throw e;
		} catch (PlainHttpException e) {
			throw new RejectedException("The key set URL of the issuer uses plain http. " + e.getMessage());
		} catch (Exception e) {
			throw new RejectedException("The key set of the issuer could not be read");
		}
	}

	// LoginClients.discover and LoginClients.issuer check the scheme and host of this URL.
	private URI jwksUriOfIssuer(Target target, String issuer) throws IOException, ParseException {
		URI document = LoginClients.wellKnown(URI.create(issuer), "openid-configuration");
		JSONObject json = JSONObjectUtils.parse(read(target, document));
		if (!issuer.equals(JSONObjectUtils.getString(json, "issuer", null))) {
			throw new RejectedException("The OpenID document of the issuer names a different issuer");
		}
		return LoginClients.resolve(document, JSONObjectUtils.getString(json, "jwks_uri", null));
	}

	private String read(Target target, URI uri) throws IOException, ParseException {
		HTTPRequest request = new HTTPRequest(HTTPRequest.Method.GET, uri);
		HTTPResponse response = clients.send(target.kind(), request);
		response.ensureStatusCode(200);
		return response.getBody();
	}
}
