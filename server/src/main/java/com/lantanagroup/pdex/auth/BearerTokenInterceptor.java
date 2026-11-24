package com.lantanagroup.pdex.auth;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.exceptions.UnclassifiedServerFailureException;
import ca.uhn.fhir.rest.server.method.PageMethodBinding;
import ca.uhn.fhir.util.OperationOutcomeUtil;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.text.ParseException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseOperationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Requires a bearer JWT from the configured issuer on each FHIR request, except for the capability
 * statement, .well-known, the public endpoints, paging requests, and requests with the bypass header.
 * The hook runs before HAPI selects a handler, so a rejected request reaches no provider.
 */
@Interceptor
public class BearerTokenInterceptor {

	/** The key in {@code RequestDetails.getUserData()} of an unmodifiable {@code Map<String, Object>} of the verified claims. */
	public static final String CLAIMS_KEY = "auth.claims";

	private static final Logger log = LoggerFactory.getLogger(BearerTokenInterceptor.class);
	private static final String BEARER = "Bearer ";
	private static final Pattern PUBLIC_ENDPOINT =
			Pattern.compile("[A-Z][A-Za-z0-9]*(/\\$[A-Za-z0-9._-]+)?|\\$[A-Za-z0-9._-]+");
	private static final Set<JWSAlgorithm> ALGORITHMS =
			Set.of(JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.ES256, JWSAlgorithm.ES384);

	private final IssuerMetadata issuerMetadata;
	private final String issuer;
	private final String audience;
	private final String jwksUri;
	private final Set<String> publicEndpoints;
	private final String bypassHeader;
	private volatile DefaultJWTProcessor<SecurityContext> processor;

	/** Without a jwksUri, the interceptor finds the key set by OpenID Connect discovery. */
	public BearerTokenInterceptor(
			IssuerMetadata issuerMetadata,
			String audience,
			String jwksUri,
			List<String> publicEndpoints,
			String bypassHeader) {
		if (StringUtils.isBlank(issuerMetadata.issuer())) {
			throw new IllegalArgumentException("app.auth.issuer needs a value when app.auth.enabled is true");
		}
		for (String entry : publicEndpoints) {
			if (entry == null || !PUBLIC_ENDPOINT.matcher(entry).matches()) {
				throw new IllegalArgumentException(
						"Public endpoint \"" + entry + "\" is not in the form Type, $operation, or Type/$operation");
			}
		}
		this.issuerMetadata = issuerMetadata;
		this.issuer = issuerMetadata.issuer();
		this.audience = StringUtils.isBlank(audience) ? null : audience;
		this.jwksUri = jwksUri;
		this.publicEndpoints = Set.copyOf(publicEndpoints);
		this.bypassHeader = StringUtils.isBlank(bypassHeader) ? null : bypassHeader;
	}

	@Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLER_SELECTED)
	public boolean authenticate(RequestDetails details) {
		if (isCapabilityStatement(details)
				|| ".well-known".equals(details.getResourceName())
				|| isPublicEndpoint(details)
				|| isPagingRequest(details)
				|| (bypassHeader != null && details.getHeader(bypassHeader) != null)) {
			return true;
		}
		String authorization = details.getHeader(Constants.HEADER_AUTHORIZATION);
		if (authorization == null || !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
			throw unauthorized(details, "Bearer", "Bearer token required");
		}
		JWTClaimsSet claims;
		try {
			claims = verify(authorization.substring(BEARER.length()).trim());
		} catch (ParseException | BadJOSEException e) {
			log.info("Bearer token rejected: {}", e.getMessage());
			throw unauthorized(details, "Bearer error=\"invalid_token\"", "Invalid bearer token");
		} catch (Exception e) {
			log.warn("The server cannot check a bearer token: {}", e.toString());
			throw unavailable(details, "The server cannot check the token");
		}
		details.getUserData().put(CLAIMS_KEY, Collections.unmodifiableMap(claims.getClaims()));
		return true;
	}

	private static boolean isCapabilityStatement(RequestDetails details) {
		return details.getResourceName() == null
				&& Constants.URL_TOKEN_METADATA.equals(details.getOperation())
				&& (details.getRequestType() == RequestTypeEnum.GET || details.getRequestType() == RequestTypeEnum.HEAD);
	}

	/** Matches the resource type and the operation that HAPI parsed and routes on. */
	private boolean isPublicEndpoint(RequestDetails details) {
		String type = details.getResourceName();
		String operation = details.getOperation();
		if (type == null) {
			return operation != null && publicEndpoints.contains(operation);
		}
		return publicEndpoints.contains(type) || (operation != null && publicEndpoints.contains(type + "/" + operation));
	}

	/** Accepts only a request that HAPI gives to its paging handler. */
	private static boolean isPagingRequest(RequestDetails details) {
		if (details.getResourceName() != null || details.getParameters().get(Constants.PARAM_PAGINGACTION) == null) {
			return false;
		}
		try {
			return details.getServer() instanceof RestfulServer server
					&& server.determineResourceMethod(details, null) instanceof PageMethodBinding;
		} catch (RuntimeException e) {
			return false;
		}
	}

	private JWTClaimsSet verify(String token) throws ParseException, JOSEException, BadJOSEException, IOException {
		SignedJWT jwt = SignedJWT.parse(token);
		JWTClaimsSet claims = processor().process(jwt, null);
		// The required-claim check of Nimbus tests only for the key, so it accepts "exp": null.
		if (claims.getExpirationTime() == null) {
			throw new BadJWTException("The token has no expiration time");
		}
		return claims;
	}

	/**
	 * Finds the key set URL with no lock held, so an issuer that is down blocks no other request. A failure stores
	 * nothing, so the next token tries again.
	 */
	private DefaultJWTProcessor<SecurityContext> processor() throws IOException {
		DefaultJWTProcessor<SecurityContext> ready = processor;
		if (ready != null) {
			return ready;
		}
		URL jwks = jwksUrl();
		synchronized (this) {
			if (processor == null) {
				// The refresh-ahead cache of Nimbus starts a thread that is not a daemon thread. The plain cache starts none.
				JWKSource<SecurityContext> keySource = JWKSourceBuilder.create(jwks, issuerMetadata.retriever())
						.refreshAheadCache(false)
						.build();
				DefaultJWTProcessor<SecurityContext> built = new DefaultJWTProcessor<>();
				built.setJWSTypeVerifier(
						new DefaultJOSEObjectTypeVerifier<>(JOSEObjectType.JWT, new JOSEObjectType("at+jwt"), null));
				built.setJWSKeySelector(new JWSVerificationKeySelector<>(ALGORITHMS, keySource));
				built.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
						audience, new JWTClaimsSet.Builder().issuer(issuer).build(), Set.of("exp")));
				processor = built;
			}
			return processor;
		}
	}

	/** Throws no ParseException, so that a ParseException in verify is always a fault of the token. */
	private URL jwksUrl() throws IOException {
		if (StringUtils.isNotBlank(jwksUri)) {
			return URI.create(jwksUri).toURL();
		}
		Map<String, Object> metadata = issuerMetadata.wellKnown("openid-configuration");
		if (!issuer.equals(metadata.get("issuer"))) {
			throw new IllegalStateException("The discovery document of " + issuer + " names another issuer");
		}
		if (!(metadata.get("jwks_uri") instanceof String jwks)) {
			throw new IllegalStateException("The discovery document of " + issuer + " has no jwks_uri");
		}
		return URI.create(jwks).toURL();
	}

	/** The body has a fixed text, because a library message can name the expected issuer. */
	private static AuthenticationException unauthorized(RequestDetails details, String challenge, String text) {
		AuthenticationException exception = new AuthenticationException(text);
		exception.setOperationOutcome(outcome(details, "security", text));
		exception.addResponseHeader("WWW-Authenticate", challenge);
		return exception;
	}

	/** Has no cause, so that HAPI does not log the reason a second time. */
	private static UnclassifiedServerFailureException unavailable(RequestDetails details, String text) {
		return new UnclassifiedServerFailureException(503, text, outcome(details, "transient", text));
	}

	private static IBaseOperationOutcome outcome(RequestDetails details, String code, String text) {
		return OperationOutcomeUtil.createOperationOutcome("error", text, code, details.getFhirContext(), null);
	}
}
