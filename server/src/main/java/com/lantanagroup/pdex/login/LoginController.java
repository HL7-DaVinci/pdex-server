package com.lantanagroup.pdex.login;

import com.lantanagroup.pdex.login.IdTokenCheck.RejectedException;
import com.lantanagroup.pdex.login.LoginClients.Discovery;
import com.lantanagroup.pdex.login.LoginClients.IssuerKeys;
import com.lantanagroup.pdex.login.LoginClients.PlainHttpException;
import com.lantanagroup.pdex.login.LoginClients.RefusedServerException;
import com.lantanagroup.pdex.login.LoginClients.Target;
import com.lantanagroup.pdex.login.LoginSession.Client;
import com.lantanagroup.pdex.login.LoginSession.ClientAuth;
import com.lantanagroup.pdex.login.LoginSession.Pending;
import com.lantanagroup.pdex.login.LoginSession.TokenSet;
import com.lantanagroup.pdex.login.UdapClient.Endpoints;
import com.lantanagroup.pdex.login.UdapClient.Offer;
import com.lantanagroup.pdex.login.UdapClient.RefusedException;
import com.lantanagroup.pdex.proxy.PublicAddresses.BlockedAddressException;
import com.nimbusds.oauth2.sdk.AccessTokenResponse;
import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant;
import com.nimbusds.oauth2.sdk.AuthorizationRequest;
import com.nimbusds.oauth2.sdk.ErrorObject;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.TokenResponse;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import com.nimbusds.oauth2.sdk.token.AccessToken;
import com.nimbusds.oauth2.sdk.token.AccessTokenType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * The endpoints of a SMART standalone launch with the server as the OAuth client: client, status, login,
 * callback, and logout. The client values and the tokens stay in the servlet session. Each refusal is short plain text with a fixed reason,
 * and no exception leaves a method, because the error dispatch of the container lets the frontend error view
 * replace the response.
 */
@RestController
public class LoginController {

	private static final Logger log = LoggerFactory.getLogger(LoginController.class);

	static final String NOT_ENABLED = "Sign-in is not enabled";
	static final String NO_PROOF = "The request does not show that the app sent it";

	private static final String ENDPOINTS_CHANGED =
			"The endpoints of the authorization server changed. Read the endpoints and sign in again.";

	private static final MediaType PLAIN_TEXT = new MediaType("text", "plain", StandardCharsets.UTF_8);

	// RFC 6454: a scheme, a host, and an optional port. A path, a query, or user information would go into the
	// redirect URI.
	private static final Pattern ORIGIN = Pattern.compile("https?://(\\[[0-9A-Fa-f:.]+\\]|[A-Za-z0-9.-]+)(:[0-9]{1,5})?");

	// RFC 6749 section 5.2: an error code has only the characters %x20-21, %x23-5B, and %x5D-7E.
	private static final Pattern ERROR_CODE = Pattern.compile("[\\x20\\x21\\x23-\\x5B\\x5D-\\x7E]+");

	// The id part has the characters and the length of the FHIR id type.
	private static final Pattern LOGIN_HINT = Pattern.compile("(Patient|Practitioner)/[A-Za-z0-9.-]{1,64}");

	private final LoginClients clients;
	private final IdTokenCheck idTokens;
	private final UdapClient udap;
	private final boolean enabled;

	public LoginController(
			LoginClients clients,
			IdTokenCheck idTokens,
			UdapClient udap,
			@Value("${app.login.enabled:false}") boolean enabled) {
		this.clients = clients;
		this.idTokens = idTokens;
		this.udap = udap;
		this.enabled = enabled;
	}

	/** Creates no session. The error of a failed sign-in for this server is in the answer one time. */
	@GetMapping("/auth/status")
	public ResponseEntity<Object> status(HttpServletRequest request, @RequestParam("server") String server) {
		if (!enabled) {
			return refuse(404, NOT_ENABLED);
		}
		if (!LoginFilter.hasProof(request)) {
			return refuse(403, NO_PROOF);
		}
		Target target;
		try {
			target = clients.target(server, origin(request), request.getLocalPort());
		} catch (PlainHttpException e) {
			// The section of the frontend shows this reason. It does not show the text of a 400.
			return ResponseEntity.ok(Map.of("offered", false, "reason", plainHttpServer(e), "plainHttp", true,
					"signedIn", false));
		} catch (RefusedServerException | IllegalArgumentException e) {
			return refuseServer(e);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		try {
			Discovery discovery = clients.discover(target);
			out.put("offered", true);
			out.put("authorizationEndpoint", discovery.authorize().toString());
			out.put("tokenEndpoint", discovery.token().toString());
			out.put("capabilities", discovery.capabilities());
		} catch (Exception e) {
			out.put("offered", false);
			out.put("reason", discoveryFailure(e));
			if (e instanceof PlainHttpException) {
				out.put("plainHttp", true);
			}
		}
		if (udap.certificate().isPresent()) {
			try {
				udap.offer(target).ifPresent(offer -> out.put("udap", Map.of("offered", true,
						"authorizationEndpoint", offer.endpoints().authorization(),
						"tokenEndpoint", offer.endpoints().token(),
						"registrationEndpoint", offer.endpoints().registration(),
						"clientUri", offer.clientUri())));
			} catch (RefusedException e) {
				out.put("udap", Map.of("offered", false, "reason", e.getMessage()));
			}
		}
		Optional<LoginSession> login = LoginSession.find(request.getSession(false));
		Optional<TokenSet> tokens = login.flatMap(session -> session.forTarget(target));
		out.put("signedIn", tokens.isPresent());
		tokens.ifPresent(set -> {
			out.put("scope", set.scope());
			putIfKnown(out, "patient", set.patient());
			putIfKnown(out, "fhirUser", set.fhirUser());
			putIfKnown(out, "expiresAt", set.expiresAt());
			out.put("patientMissing", set.patientMissing());
		});
		// Only these values go into the answer, so the secret never leaves the session.
		login.flatMap(session -> session.client(target)).ifPresent(client -> out.put("client", client.udap()
				? Map.of("method", "udap", "scope", client.scope())
				: Map.of("method", "client", "clientId", client.clientId(), "scope", client.scope(), "hasSecret",
						client.clientAuth() == ClientAuth.BASIC)));
		login.map(session -> session.takeError(server)).ifPresent(error -> out.put("error", error));
		return ResponseEntity.ok(out);
	}

	/**
	 * Keeps the client values for the server in the session. An empty secret keeps the saved secret when the saved
	 * client id is equal, else it saves a public client. A save of method udap sets the method and the scope and
	 * keeps the saved client id and secret. Reads no discovery document and calls no server.
	 */
	@PostMapping("/auth/client")
	public ResponseEntity<Object> saveClient(HttpServletRequest request, @RequestBody Map<String, String> body) {
		if (!enabled) {
			return refuse(404, NOT_ENABLED);
		}
		if (!LoginFilter.hasProof(request)) {
			return refuse(403, NO_PROOF);
		}
		// An absent method is the method client.
		String method = Objects.requireNonNullElse(body.get("method"), "client");
		if (!method.equals("client") && !method.equals("udap")) {
			return refuse(400, "The method must be client or udap");
		}
		boolean udapMethod = method.equals("udap");
		String clientId = body.get("clientId");
		String scope = body.get("scope");
		if (!udapMethod && (clientId == null || clientId.isBlank()) || scope == null || scope.isBlank()) {
			return refuse(400, udapMethod ? "The scope must not be empty" : "The client id and the scope must not be empty");
		}
		Target target;
		try {
			target = clients.target(body.get("server"), origin(request), request.getLocalPort());
		} catch (RefusedServerException | IllegalArgumentException e) {
			return refuseServer(e);
		}
		String secret = body.get("clientSecret");
		// A session id that a different site planted in the browser must never hold a client secret, so the id
		// changes.
		HttpSession session = request.getSession(true);
		request.changeSessionId();
		LoginSession login = LoginSession.of(session);
		Optional<Client> saved = login.client(target);
		if (udapMethod) {
			login.putClient(new Client(target.kind(), target.fhirBaseWithoutSlash(), saved.map(Client::clientId).orElse(""),
					saved.map(Client::secret).orElse(""), scope, true));
			return ResponseEntity.noContent().build();
		}
		// The browser does not hold the saved secret, so it cannot send that secret with a second save.
		if (secret == null || secret.isEmpty()) {
			secret = saved.filter(client -> client.clientId().equals(clientId)).map(Client::secret).orElse("");
		}
		login.putClient(new Client(target.kind(), target.fhirBaseWithoutSlash(), clientId, secret, scope, false));
		return ResponseEntity.noContent().build();
	}

	/**
	 * Uses the client values that the session holds for the server. A body with a loginHint can also give the scope
	 * of this sign-in, which replaces the saved scope for this sign-in only. The values of the body are not of
	 * type String, because skipForm can be a JSON boolean.
	 */
	@PostMapping("/auth/login")
	public ResponseEntity<Object> login(HttpServletRequest request, @RequestBody Map<String, ?> body) {
		if (!enabled) {
			return refuse(404, NOT_ENABLED);
		}
		if (!LoginFilter.hasProof(request)) {
			return refuse(403, NO_PROOF);
		}
		String origin = request.getHeader(HttpHeaders.ORIGIN);
		if (!isOrigin(origin)) {
			return refuse(400, "The request has no valid Origin header");
		}
		// The callback URL is on this origin, so the authorization code goes to it over the network.
		try {
			LoginClients.requireHttp(URI.create(origin));
		} catch (IllegalArgumentException e) {
			return refuse(400, e instanceof PlainHttpException
					? "The address of the app uses plain http. " + e.getMessage()
					: "The request has no valid Origin header");
		}
		// A refused member changes no session and sends no request to another server.
		String loginHint = member(body, "loginHint");
		boolean skipForm = "true".equals(member(body, "skipForm"));
		String bodyScope = member(body, "scope");
		if (loginHint != null && !LOGIN_HINT.matcher(loginHint).matches()) {
			return refuse(400, "The loginHint must be Patient/<id> or Practitioner/<id>");
		}
		if (skipForm && loginHint == null) {
			return refuse(400, "skipForm needs a loginHint");
		}
		if (bodyScope != null && loginHint == null) {
			return refuse(400, "scope needs a loginHint");
		}
		if (bodyScope != null && bodyScope.isBlank()) {
			return refuse(400, "The scope must not be empty");
		}
		Target target;
		try {
			target = clients.target(member(body, "server"), origin, request.getLocalPort());
		} catch (RefusedServerException | IllegalArgumentException e) {
			return refuseServer(e);
		}
		Client client = LoginSession.find(request.getSession(false)).flatMap(login -> login.client(target)).orElse(null);
		if (client == null) {
			return refuse(400, "The session has no client values for this server. Save them first.");
		}
		// The registration, the authorize request, and the pending sign-in use this scope. The saved client values
		// keep their scope.
		String scope = Objects.requireNonNullElse(bodyScope, client.scope());
		URI redirectUri = URI.create(origin + "/auth/callback");
		String clientId;
		Discovery discovery;
		if (client.udap()) {
			Offer offer;
			try {
				offer = udap.offer(target).orElse(null);
			} catch (RefusedException e) {
				return refuse(502, e.getMessage());
			}
			if (offer == null) {
				return refuse(400, "No UDAP sign-in is offered for this server");
			}
			// The registration, the sign-in, and the client assertion go only to the endpoints that the dialog showed.
			Endpoints endpoints = offer.endpoints();
			if (!endpoints.authorization().equals(member(body, "authorizationEndpoint"))
					|| !endpoints.token().equals(member(body, "tokenEndpoint"))
					|| !endpoints.registration().equals(member(body, "registrationEndpoint"))) {
				return refuse(409, ENDPOINTS_CHANGED);
			}
			Optional<IssuerKeys> issuer;
			try {
				issuer = clients.issuer(target);
			} catch (IllegalArgumentException e) {
				return refuse(502, "The issuer or the jwks_uri of the SMART configuration of the server is not an https"
						+ " URL, or an http URL on localhost or a loopback address");
			}
			try {
				clientId = udap.register(target, offer, redirectUri, scope);
			} catch (RefusedException e) {
				return refuse(502, e.getMessage());
			}
			discovery = new Discovery(URI.create(endpoints.authorization()), endpoints.authorization(),
					URI.create(endpoints.token()), endpoints.token(), List.of(),
					issuer.map(IssuerKeys::issuer).orElse(null), issuer.map(IssuerKeys::jwksUri).orElse(null));
		} else {
			try {
				discovery = clients.discover(target);
			} catch (Exception e) {
				return refuse(502, discoveryFailure(e));
			}
			// The sign-in and the client secret go only to the endpoints that the dialog showed. A server can name
			// other endpoints in this read than in the read of the status call.
			if (!discovery.authorize().toString().equals(member(body, "authorizationEndpoint"))
					|| !discovery.token().toString().equals(member(body, "tokenEndpoint"))) {
				return refuse(409, ENDPOINTS_CHANGED);
			}
			clientId = client.clientId();
		}
		State state = new State();
		CodeVerifier verifier = new CodeVerifier();
		AuthorizationRequest.Builder authorize = new AuthorizationRequest.Builder(ResponseType.CODE, new ClientID(clientId))
				.endpointURI(discovery.authorize())
				.redirectionURI(redirectUri)
				.scope(Scope.parse(scope))
				.state(state)
				.codeChallenge(verifier, CodeChallengeMethod.S256)
				.customParameter("aud", target.fhirBase().toString());
		if (loginHint != null) {
			authorize.customParameter("login_hint", loginHint);
		}
		if (skipForm) {
			authorize.customParameter("hl7fast_signin", "auto");
		}
		URI authorizeUrl = authorize.build().toURI();
		// A session id that a different site planted in the browser must never hold a sign-in, so the id changes.
		HttpSession session = request.getSession(true);
		request.changeSessionId();
		LoginSession.of(session).start(new Pending(member(body, "server"), target, clientId, client.clientAuth(),
				client.udap() ? null : client.secret(), scope, redirectUri, state, verifier, discovery));
		return ResponseEntity.ok(Map.of("authorizeUrl", authorizeUrl.toString()));
	}

	/** Needs no proof that the app sent the request, because the browser comes from the authorization server. */
	@GetMapping("/auth/callback")
	public ResponseEntity<Object> callback(HttpServletRequest request,
			@RequestParam(name = "state", required = false) String state,
			@RequestParam(name = "code", required = false) String code,
			@RequestParam(name = "error", required = false) String error) {
		if (!enabled) {
			return refuse(404, NOT_ENABLED);
		}
		// The pending sign-in leaves the session before any other work, and only for its own state. A second
		// callback then finds none, so the code goes to the token endpoint one time only.
		LoginSession login = LoginSession.find(request.getSession(false)).orElse(null);
		Pending pending = login == null ? null : login.takePending(state);
		if (pending == null) {
			return refuse(400, "No sign-in is pending for this state");
		}
		String failure;
		if (code == null || code.isBlank()) {
			failure = "The authorization server refused the sign-in" + errorCode(error);
		} else {
			try {
				failure = signInFailure(request, login, pending, code);
			} catch (RuntimeException e) {
				// The pending sign-in is already gone, so the status must show the failure.
				log.warn("The sign-in failed after the callback: {}", e.getClass().getName());
				failure = "The sign-in failed";
			}
		}
		if (failure != null) {
			login.error(pending.server(), failure);
		}
		// The path is fixed, so no value of the callback goes into the redirect.
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/")).build();
	}

	// Spring throws these before a method of this controller runs. Their message can hold the text of the request
	// body, for example a client secret in JSON that is not valid. So the answer has a fixed reason, and nothing
	// goes to the log.
	@ExceptionHandler({MissingServletRequestParameterException.class, HttpMessageNotReadableException.class,
			HttpMediaTypeNotSupportedException.class})
	public ResponseEntity<Object> badRequest() {
		return refuse(400, "The request has a missing parameter or a body that is not valid JSON");
	}

	/** Removes the token set and keeps the client values. Sends nothing to the authorization server. */
	@PostMapping("/auth/logout")
	public ResponseEntity<Object> logout(HttpServletRequest request, @RequestBody Map<String, String> body) {
		if (!enabled) {
			return refuse(404, NOT_ENABLED);
		}
		if (!LoginFilter.hasProof(request)) {
			return refuse(403, NO_PROOF);
		}
		Target target;
		try {
			target = clients.target(body.get("server"), origin(request), request.getLocalPort());
		} catch (RefusedServerException | IllegalArgumentException e) {
			return refuseServer(e);
		}
		LoginSession.find(request.getSession(false))
				.ifPresent(login -> login.remove(target.kind(), target.fhirBaseWithoutSlash()));
		return ResponseEntity.noContent().build();
	}

	/** Exchanges the code and keeps the token set. Gives null on success, else the reason of the failure. */
	private String signInFailure(HttpServletRequest request, LoginSession login, Pending pending, String code) {
		TokenResponse response;
		try {
			response = TokenResponse.parse(clients.send(pending.target().kind(), tokenRequest(pending, code).toHTTPRequest()));
		} catch (ParseException e) {
			return "The token response could not be read";
		} catch (Exception e) {
			URI endpoint = pending.discovery().token();
			// User information in the URL can hold a secret, and a cause message can hold the full request URL. So
			// the log gives only the host, the port, and the class of the cause.
			log.warn("The token call to {}://{}{} failed: {}", endpoint.getScheme(), endpoint.getHost(),
					endpoint.getPort() < 0 ? "" : ":" + endpoint.getPort(),
					NestedExceptionUtils.getMostSpecificCause(e).getClass().getName());
			return ExceptionUtils.throwableOfType(e, BlockedAddressException.class) != null
					? "The address of the token endpoint is not public"
					: "The token call failed";
		}
		if (!response.indicatesSuccess()) {
			ErrorObject errorObject = response.toErrorResponse().getErrorObject();
			return "The token endpoint answered with an error" + errorCode(errorObject == null ? null : errorObject.getCode());
		}
		AccessTokenResponse success = response.toSuccessResponse();
		AccessToken token = success.getTokens().getAccessToken();
		// The SDK also accepts a DPoP token, which this server cannot use.
		if (!AccessTokenType.BEARER.equals(token.getType())) {
			return "The token type is not bearer";
		}
		Object idToken = success.getCustomParameters().get("id_token");
		Optional<String> fhirUser;
		try {
			fhirUser = idToken == null
					? Optional.empty()
					: idTokens.fhirUser(pending.target(), pending.discovery(), pending.clientId(), idToken.toString());
		} catch (RejectedException e) {
			return e.getMessage();
		}
		String scope = token.getScope() == null ? pending.scope() : token.getScope().toString();
		Object patient = success.getCustomParameters().get("patient");
		boolean patientMissing = patient == null
				&& Arrays.stream(scope.split("\\s+")).anyMatch(value -> value.startsWith("patient/"));
		// With no expires_in the lifetime is 0, and the token set stays until sign-out or the end of the session.
		Instant expiresAt = token.getLifetime() > 0 ? Instant.now().plusSeconds(token.getLifetime()) : null;
		// A session id that was known before the token call must never hold the token set.
		request.changeSessionId();
		login.put(new TokenSet(pending.target().kind(), pending.target().fhirBaseWithoutSlash(), token.getValue(),
				expiresAt, scope, patient == null ? null : patient.toString(), fhirUser.orElse(null), patientMissing));
		return null;
	}

	// Method basic sends the client id and the secret in HTTP Basic. Method none is a public client and sends
	// the client id in the body. Method udap sends a client assertion that the certificate of this app signs, with
	// the signed token_endpoint as published for aud, and udap=1.
	private TokenRequest tokenRequest(Pending pending, String code) {
		AuthorizationCodeGrant grant = new AuthorizationCodeGrant(new AuthorizationCode(code), pending.redirectUri(),
				pending.verifier());
		ClientID clientId = new ClientID(pending.clientId());
		URI endpoint = pending.discovery().token();
		return switch (pending.clientAuth()) {
			case BASIC -> new TokenRequest(endpoint, new ClientSecretBasic(clientId, new Secret(pending.secret())), grant, null);
			case NONE -> new TokenRequest(endpoint, clientId, grant, null);
			case UDAP -> new TokenRequest.Builder(endpoint,
					udap.assertion(pending.clientId(), pending.discovery().tokenPublished()), grant)
					.customParameter("udap", "1")
					.build();
		};
	}

	// A same-origin GET has no Origin header, so the origin then comes from the request URL. Only the own server
	// uses the origin, as its FHIR base.
	private static String origin(HttpServletRequest request) {
		String origin = request.getHeader(HttpHeaders.ORIGIN);
		return isOrigin(origin)
				? origin
				: ServletUriComponentsBuilder.fromContextPath(request).replacePath(null).build().toUriString();
	}

	// The pattern lets through an IPv6 literal that java.net.URI refuses, so the parse is the last check.
	private static boolean isOrigin(String value) {
		if (value == null || !ORIGIN.matcher(value).matches()) {
			return false;
		}
		try {
			new URI(value);
			return true;
		} catch (URISyntaxException e) {
			return false;
		}
	}

	// The reasons are fixed, because the message of an exception from LoginClients can hold a URL.
	private static String discoveryFailure(Exception e) {
		if (ExceptionUtils.throwableOfType(e, BlockedAddressException.class) != null) {
			return "The address of the server is not public";
		}
		if (e instanceof PlainHttpException) {
			return "The SMART configuration of the server has a plain http URL. " + e.getMessage();
		}
		return e instanceof IllegalArgumentException
				? "The SMART configuration of the server lacks an endpoint or has a value that is not an absolute http"
						+ " or https URL"
				: "The SMART configuration of the server could not be read";
	}

	private static ResponseEntity<Object> refuseServer(RuntimeException e) {
		if (e instanceof RefusedServerException) {
			return refuse(403, "The server is not under a listed server, and passthrough is off");
		}
		return e instanceof PlainHttpException
				? refuse(400, plainHttpServer(e))
				: refuse(400, "The server value is not a valid URL");
	}

	private static String plainHttpServer(RuntimeException e) {
		return "The server URL uses plain http. " + e.getMessage();
	}

	static String errorCode(String code) {
		return code != null && ERROR_CODE.matcher(code).matches() ? ": " + code : "";
	}

	// String.valueOf of null gives the text "null", so an absent member stays null.
	private static String member(Map<String, ?> body, String name) {
		Object value = body.get(name);
		return value == null ? null : String.valueOf(value);
	}

	private static void putIfKnown(Map<String, Object> out, String name, Object value) {
		if (value != null) {
			out.put(name, value.toString());
		}
	}

	private static ResponseEntity<Object> refuse(int status, String reason) {
		return ResponseEntity.status(status).contentType(PLAIN_TEXT).body(reason);
	}
}
