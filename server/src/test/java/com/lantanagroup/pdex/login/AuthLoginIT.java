package com.lantanagroup.pdex.login;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.jpa.starter.Application;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.RestfulServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.X509CertUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.oauth2.sdk.pkce.CodeChallenge;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import com.nimbusds.oauth2.sdk.util.URLUtils;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.security.KeyStore;
import java.security.cert.CertificateEncodingException;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots the full server with the sign-in and the proxy on. It runs a SMART standalone launch for the own server
 * and for a listed server, and a UDAP sign-in for a listed server. A JDK HTTP stub on 127.0.0.1 is the
 * authorization server. Four paths of the stub are listed FHIR servers. One publishes a SMART configuration, one
 * publishes signed UDAP metadata and a SMART configuration with only an issuer, and two publish neither. A test
 * filter serves the SMART configuration of the own server, a second filter answers 404 for its UDAP metadata, and
 * a HAPI interceptor records the Authorization header that HAPI sees. A LoginTestKeys key is the udapCertificate
 * bean and also signs the UDAP metadata of the stub.
 *
 * <p>The requests use java.net.http.HttpClient with no cookie handler and no redirects, so each test sends the
 * session cookie itself and sees each 302. The test sets no session property, so the cookie and the session
 * tracking come from the installed application.yaml.
 */
@ActiveProfiles("test")
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		classes = {Application.class},
		properties = {
			"spring.datasource.url=jdbc:h2:mem:authloginit",
			// Sets the FHIR version of the server project. The application-test.yaml of the starter has R4.
			"hapi.fhir.fhir_version=R4",
			"app.login.enabled=true",
			"app.proxy.enabled=true",
			"spring.ai.mcp.server.enabled=false",
			"spring.main.allow-bean-definition-overriding=true",
			"management.health.elasticsearch.enabled=false",
			"spring.jpa.properties.hibernate.search.backend.directory.type=local-heap"
		})
@Import(AuthLoginIT.TestBeans.class)
class AuthLoginIT {

	private static final String CLIENT_ID = "login-it";
	// The characters + / = % : have a special meaning in a form value or in HTTP Basic.
	private static final String CLIENT_SECRET = "s+/=%:x";
	// The default scope of the sign-in section in the frontend template.
	private static final String SCOPE = "openid fhirUser launch/patient patient/*.rs";
	// Differs from SCOPE, so the status must show the scope of the token response.
	private static final String GRANTED_SCOPE = "openid fhirUser patient/*.rs";
	private static final String ACCESS_TOKEN = "at-5d1e9b77c0a4";
	// Differs from ACCESS_TOKEN, so each request shows which token set the filter chose.
	private static final String LISTED_ACCESS_TOKEN = "at-8c2f61a9d3e7";
	private static final String UDAP_ACCESS_TOKEN = "at-3a7f0c9e5b21";
	private static final String UDAP_CLIENT_ID = "udap-client-1";
	// The id has a hyphen and a period, which a FHIR id permits.
	private static final String PICKED_HINT = "Practitioner/it-pr-1.a";
	// user/Practitioner.rs is not in the scopes_supported of the UDAP metadata of the stub, so the software
	// statement shows which scope the registration got.
	private static final String PICKED_SCOPE = "openid fhirUser user/*.rs user/Practitioner.rs";
	private static final String NO_PENDING = "No sign-in is pending for this state";
	private static final String NO_HEADER = "<none>";

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final RSAKey KEY = rsaKey();
	// No key set of the stub holds this key.
	private static final RSAKey UNKNOWN_KEY = rsaKey();
	private static final Stub STUB = new Stub();
	// The URI subject alternative name is the FHIR base URL of the UDAP entry, so the key of this app also passes
	// the metadata checks of that entry.
	private static final KeyStore.PrivateKeyEntry UDAP_KEY = LoginTestKeys.create("uri:" + STUB.base() + "/udap/fhir");
	private static final List<String> HAPI_AUTHORIZATION = new CopyOnWriteArrayList<>();

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void fhirServers(DynamicPropertyRegistry registry) {
		registry.add("app.fhir.servers", () -> "[{\"name\":\"Listed\",\"url\":\"" + STUB.base() + "/listed/fhir\"},"
				+ "{\"name\":\"No discovery\",\"url\":\"" + STUB.base() + "/no-discovery/fhir\"},"
				+ "{\"name\":\"HTML page\",\"url\":\"" + STUB.base() + "/html/fhir\"},"
				+ "{\"name\":\"UDAP\",\"url\":\"" + STUB.base() + "/udap/fhir\"}]");
	}

	@BeforeEach
	void clear() {
		STUB.received.clear();
		HAPI_AUTHORIZATION.clear();
	}

	@AfterAll
	static void stopStub() {
		STUB.stop();
	}

	@Test
	void signInUseAndSignOut() throws Exception {
		HttpResponse<String> status = status("/fhir", null, true);
		assertEquals(200, status.statusCode(), status.body());
		JsonNode offered = json(status);
		assertTrue(offered.get("offered").asBoolean());
		assertEquals(STUB.base() + "/authorize", offered.get("authorizationEndpoint").asText());
		assertEquals(STUB.base() + "/token", offered.get("tokenEndpoint").asText());
		assertFalse(offered.get("signedIn").asBoolean());
		assertEquals(List.of(), status.headers().allValues("Set-Cookie"));
		assertEquals(403, status("/fhir", null, false).statusCode());
		String saved = save("/fhir", null);
		HttpResponse<String> changed = login("/fhir", saved, offered.get("authorizationEndpoint").asText(),
				STUB.base() + "/other-token");
		assertEquals(409, changed.statusCode(), changed.body());
		assertEquals(List.of(), changed.headers().allValues("Set-Cookie"));

		HttpResponse<String> login = login("/fhir", saved);
		assertEquals(200, login.statusCode(), login.body());
		String loginCookie = sessionCookie(login);
		assertTrue(loginCookie.contains("HttpOnly"), loginCookie);
		assertTrue(loginCookie.contains("SameSite=Lax"), loginCookie);
		String authorizeUrl = json(login).get("authorizeUrl").asText();
		assertTrue(authorizeUrl.startsWith(STUB.base() + "/authorize?"), authorizeUrl);
		Map<String, String> authorize = parameters(URI.create(authorizeUrl).getRawQuery());
		assertEquals("code", authorize.get("response_type"));
		assertEquals(CLIENT_ID, authorize.get("client_id"));
		assertEquals(origin() + "/auth/callback", authorize.get("redirect_uri"));
		assertEquals(SCOPE, authorize.get("scope"));
		assertNotNull(authorize.get("state"));
		assertEquals(origin() + "/fhir", authorize.get("aud"));
		assertNotNull(authorize.get("code_challenge"));
		assertEquals("S256", authorize.get("code_challenge_method"));
		for (String absent : List.of("nonce", "launch", "max_age")) {
			assertFalse(authorize.containsKey(absent), absent);
		}

		String loginSession = sessionId(loginCookie);
		HttpResponse<String> callback = send(request("/auth/callback?code=code-7f3a91c2e4&state=" + enc(authorize.get("state")),
				loginSession, false));
		assertEquals(302, callback.statusCode(), callback.body());
		assertEquals(Optional.of("/"), callback.headers().firstValue("Location"));
		String session = sessionId(sessionCookie(callback));
		assertNotEquals(loginSession, session);

		List<Received> tokenCalls = STUB.at("/token");
		assertEquals(1, tokenCalls.size());
		String basic = tokenCalls.get(0).headers().getFirst("Authorization");
		assertTrue(basic.startsWith("Basic "), basic);
		String credentials = new String(Base64.getDecoder().decode(basic.substring("Basic ".length())), UTF_8);
		// RFC 6749 section 2.3.1: the client id and the secret are form-encoded before the Basic encoding.
		assertEquals("login-it:s%2B%2F%3D%25%3Ax", credentials);
		int colon = credentials.indexOf(':');
		assertEquals(CLIENT_ID, URLDecoder.decode(credentials.substring(0, colon), UTF_8));
		assertEquals(CLIENT_SECRET, URLDecoder.decode(credentials.substring(colon + 1), UTF_8));
		Map<String, String> form = parameters(tokenCalls.get(0).body());
		assertEquals("authorization_code", form.get("grant_type"));
		assertEquals("code-7f3a91c2e4", form.get("code"));
		assertEquals(origin() + "/auth/callback", form.get("redirect_uri"));
		assertEquals(authorize.get("code_challenge"),
				CodeChallenge.compute(CodeChallengeMethod.S256, new CodeVerifier(form.get("code_verifier"))).getValue());
		assertFalse(form.containsKey("client_id"));

		HttpResponse<String> signedInStatus = status("/fhir", session, true);
		assertFalse(signedInStatus.body().contains(ACCESS_TOKEN));
		assertFalse(signedInStatus.body().contains(CLIENT_SECRET));
		JsonNode signedIn = json(signedInStatus);
		assertTrue(signedIn.get("signedIn").asBoolean());
		assertEquals(GRANTED_SCOPE, signedIn.get("scope").asText());
		assertEquals("123", signedIn.get("patient").asText());
		assertEquals(origin() + "/fhir/Patient/123", signedIn.get("fhirUser").asText());
		assertFalse(signedIn.get("patientMissing").asBoolean());
		long expiresIn = Duration.between(Instant.now(), Instant.parse(signedIn.get("expiresAt").asText())).toSeconds();
		assertTrue(expiresIn > 3500 && expiresIn <= 3600, String.valueOf(expiresIn));

		assertEquals(200, send(request("/fhir/Patient", session, true)).statusCode());
		assertEquals(List.of("Bearer " + ACCESS_TOKEN), HAPI_AUTHORIZATION);
		HttpResponse<String> config = send(request("/config.js", null, false));
		assertTrue(config.body().contains("\"login\":{"), config.body());

		String listed = STUB.base() + "/listed/fhir";
		HttpResponse<String> listedLogin = login(listed, save(listed, session));
		assertEquals(200, listedLogin.statusCode(), listedLogin.body());
		String listedState = parameters(URI.create(json(listedLogin).get("authorizeUrl").asText()).getRawQuery()).get("state");
		HttpResponse<String> listedCallback = send(request("/auth/callback?code=code-4e9a0c37f1&state=" + enc(listedState),
				sessionId(sessionCookie(listedLogin)), false));
		assertEquals(302, listedCallback.statusCode(), listedCallback.body());
		session = sessionId(sessionCookie(listedCallback));
		assertEquals(listed + "/Patient/456", json(status(listed, session, true)).get("fhirUser").asText());
		String proxyTarget = "/proxy?url=" + enc(listed + "/Patient");
		assertEquals(200, send(request(proxyTarget, session, true)).statusCode());
		assertEquals(200, send(request(proxyTarget, session, false)).statusCode());
		assertEquals(List.of("Bearer " + LISTED_ACCESS_TOKEN, NO_HEADER), STUB.at("/listed/fhir/Patient").stream()
				.map(received -> Optional.ofNullable(received.headers().getFirst("Authorization")).orElse(NO_HEADER))
				.toList());
		HAPI_AUTHORIZATION.clear();
		assertEquals(200, send(request("/fhir/Patient", session, true)).statusCode());
		assertEquals(List.of("Bearer " + ACCESS_TOKEN), HAPI_AUTHORIZATION);

		HttpResponse<String> logout = send(request("/auth/logout", session, true)
				.header("Content-Type", "application/json")
				.POST(BodyPublishers.ofString("{\"server\":\"/fhir\"}")));
		assertEquals(204, logout.statusCode(), logout.body());
		HAPI_AUTHORIZATION.clear();
		assertEquals(200, send(request("/fhir/Patient", session, true)).statusCode());
		assertEquals(List.of(NO_HEADER), HAPI_AUTHORIZATION);
		JsonNode signedOut = json(status("/fhir", session, true));
		assertFalse(signedOut.get("signedIn").asBoolean());
		assertEquals(CLIENT_ID, signedOut.get("client").get("clientId").asText());
	}

	@Test
	void udapSignInRegistersAtEachStartAndSignsTheTokenCall() throws Exception {
		String server = STUB.base() + "/udap/fhir";
		JsonNode status = json(status(server, null, true));
		// The SMART configuration of this server has no endpoints, so only the UDAP sign-in is offered.
		assertFalse(status.get("offered").asBoolean());
		JsonNode udap = status.get("udap");
		assertTrue(udap.get("offered").asBoolean(), status.toString());
		assertEquals(STUB.base() + "/udap/authorize", udap.get("authorizationEndpoint").asText());
		assertEquals(STUB.base() + "/udap/token", udap.get("tokenEndpoint").asText());
		assertEquals(STUB.base() + "/udap/register", udap.get("registrationEndpoint").asText());
		assertEquals(server, udap.get("clientUri").asText());

		HttpResponse<String> saved = post("/auth/client", null, Map.of("server", server, "method", "udap", "scope", SCOPE));
		assertEquals(204, saved.statusCode(), saved.body());
		String session = sessionId(sessionCookie(saved));
		assertEquals(JSON.valueToTree(Map.of("method", "udap", "scope", SCOPE)),
				json(status(server, session, true)).get("client"));

		HttpResponse<String> login = udapLogin(server, session);
		assertEquals(200, login.statusCode(), login.body());
		String authorizeUrl = json(login).get("authorizeUrl").asText();
		assertTrue(authorizeUrl.startsWith(STUB.base() + "/udap/authorize?"), authorizeUrl);
		Map<String, String> authorize = parameters(URI.create(authorizeUrl).getRawQuery());
		assertEquals(UDAP_CLIENT_ID, authorize.get("client_id"));
		assertEquals(origin() + "/auth/callback", authorize.get("redirect_uri"));
		assertEquals(server, authorize.get("aud"));
		assertEquals("S256", authorize.get("code_challenge_method"));
		assertEquals(SCOPE, authorize.get("scope"));

		List<Received> registrations = STUB.at("/udap/register");
		assertEquals(1, registrations.size());
		assertNull(registrations.get(0).headers().getFirst("Authorization"));
		Map<?, ?> registration = JSON.readValue(registrations.get(0).body(), Map.class);
		assertEquals(Set.of("software_statement", "udap"), registration.keySet());
		assertEquals("1", registration.get("udap"));
		JWTClaimsSet statement = signedByUdapKey((String) registration.get("software_statement"));
		assertEquals(server, statement.getIssuer());
		assertEquals(server, statement.getSubject());
		assertEquals(List.of(STUB.base() + "/udap/register"), statement.getAudience());
		assertEquals(List.of(origin() + "/auth/callback"), statement.getStringListClaim("redirect_uris"));
		// The scopes_supported values of the metadata with no system/ value. SCOPE has no value outside that list.
		String registeredScope = "openid fhirUser launch/patient patient/*.rs user/*.rs";
		assertEquals(registeredScope, statement.getStringClaim("scope"));
		assertEquals(List.of("authorization_code"), statement.getStringListClaim("grant_types"));
		assertEquals("private_key_jwt", statement.getStringClaim("token_endpoint_auth_method"));
		// The installed application.yaml gives these three values.
		assertEquals(List.of("mailto:admin@localhost"), statement.getStringListClaim("contacts"));
		assertEquals("https://build.fhir.org/icon-fhir-16.png", statement.getStringClaim("logo_uri"));
		assertFalse(statement.getStringClaim("client_name").isBlank());

		HttpResponse<String> callback = send(request("/auth/callback?code=code-9d4c1e7a20&state="
				+ enc(authorize.get("state")), sessionId(sessionCookie(login)), false));
		assertEquals(302, callback.statusCode(), callback.body());
		assertEquals(Optional.of("/"), callback.headers().firstValue("Location"));
		session = sessionId(sessionCookie(callback));

		List<Received> tokenCalls = STUB.at("/udap/token");
		assertEquals(1, tokenCalls.size());
		assertNull(tokenCalls.get(0).headers().getFirst("Authorization"));
		Map<String, String> form = parameters(tokenCalls.get(0).body());
		assertEquals("authorization_code", form.get("grant_type"));
		assertEquals("code-9d4c1e7a20", form.get("code"));
		assertEquals(origin() + "/auth/callback", form.get("redirect_uri"));
		assertEquals(authorize.get("code_challenge"),
				CodeChallenge.compute(CodeChallengeMethod.S256, new CodeVerifier(form.get("code_verifier"))).getValue());
		assertEquals("urn:ietf:params:oauth:client-assertion-type:jwt-bearer", form.get("client_assertion_type"));
		assertEquals("1", form.get("udap"));
		JWTClaimsSet assertion = signedByUdapKey(form.get("client_assertion"));
		assertEquals(UDAP_CLIENT_ID, assertion.getIssuer());
		assertEquals(UDAP_CLIENT_ID, assertion.getSubject());
		assertEquals(List.of(STUB.base() + "/udap/token"), assertion.getAudience());

		JsonNode signedIn = json(status(server, session, true));
		assertTrue(signedIn.get("signedIn").asBoolean());
		assertEquals(server + "/Patient/123", signedIn.get("fhirUser").asText());

		assertEquals(200, send(request("/proxy?url=" + enc(server + "/Patient"), session, true)).statusCode());
		assertEquals(List.of("Bearer " + UDAP_ACCESS_TOKEN), STUB.at("/udap/fhir/Patient").stream()
				.map(received -> received.headers().getFirst("Authorization"))
				.toList());

		HttpResponse<String> second = udapLogin(server, session);
		assertEquals(200, second.statusCode(), second.body());
		registrations = STUB.at("/udap/register");
		assertEquals(2, registrations.size());
		Map<?, ?> secondRegistration = JSON.readValue(registrations.get(1).body(), Map.class);
		assertEquals(registeredScope,
				signedByUdapKey((String) secondRegistration.get("software_statement")).getStringClaim("scope"));
	}

	@Test
	void loginHintSkipFormAndScopeOfTheBody() throws Exception {
		String server = STUB.base() + "/udap/fhir";
		HttpResponse<String> saved = post("/auth/client", null, Map.of("server", server, "method", "udap", "scope", SCOPE));
		assertEquals(204, saved.statusCode(), saved.body());
		String session = sessionId(sessionCookie(saved));
		JsonNode udap = json(status(server, session, true)).get("udap");
		Map<String, Object> endpoints = Map.of("server", server,
				"authorizationEndpoint", udap.get("authorizationEndpoint").asText(),
				"tokenEndpoint", udap.get("tokenEndpoint").asText(),
				"registrationEndpoint", udap.get("registrationEndpoint").asText());

		// skipForm is a JSON boolean, as the frontend sends it.
		Map<String, Object> picked = new LinkedHashMap<>(endpoints);
		picked.put("loginHint", PICKED_HINT);
		picked.put("skipForm", Boolean.TRUE);
		picked.put("scope", PICKED_SCOPE);
		HttpResponse<String> login = post("/auth/login", session, picked);
		assertEquals(200, login.statusCode(), login.body());
		Map<String, String> authorize = parameters(URI.create(json(login).get("authorizeUrl").asText()).getRawQuery());
		assertEquals(PICKED_HINT, authorize.get("login_hint"));
		assertEquals("auto", authorize.get("hl7fast_signin"));
		assertEquals(PICKED_SCOPE, authorize.get("scope"));
		List<Received> registrations = STUB.at("/udap/register");
		assertEquals(1, registrations.size());
		Map<?, ?> registration = JSON.readValue(registrations.get(0).body(), Map.class);
		// The scopes_supported values of the metadata with no system/ value, then the one value of PICKED_SCOPE
		// outside that list.
		assertEquals("openid fhirUser launch/patient patient/*.rs user/*.rs user/Practitioner.rs",
				signedByUdapKey((String) registration.get("software_statement")).getStringClaim("scope"));

		// The sign-in changes the session id. This session holds the client values, so only the check of the
		// loginHint can refuse the call.
		Map<String, Object> wrongType = new LinkedHashMap<>(endpoints);
		wrongType.put("loginHint", "Observation/1");
		HttpResponse<String> refused = post("/auth/login", sessionId(sessionCookie(login)), wrongType);
		assertEquals(400, refused.statusCode(), refused.body());
		assertEquals("The loginHint must be Patient/<id> or Practitioner/<id>", refused.body());
		assertEquals(1, STUB.at("/udap/register").size());
	}

	@Test
	void serverWithNoDiscoveryIsNotOffered() throws Exception {
		for (String server : List.of("/no-discovery/fhir", "/html/fhir")) {
			HttpResponse<String> status = status(STUB.base() + server, null, true);
			assertEquals(200, status.statusCode(), server);
			JsonNode body = json(status);
			assertFalse(body.get("offered").asBoolean(), server);
			assertFalse(body.get("reason").asText().isBlank(), server);
			assertFalse(body.has("udap"), server);
			// The stub got the discovery read, so the answer comes from the 404 or the HTML page.
			assertEquals(1, STUB.at(server + "/.well-known/smart-configuration").size(), server);
		}
	}

	@Test
	void sessionIdInTheUrlIsIgnored() throws Exception {
		HttpResponse<String> login = login("/fhir", save("/fhir", null));
		assertEquals(200, login.statusCode(), login.body());
		String loginSession = sessionId(sessionCookie(login));
		String state = parameters(URI.create(json(login).get("authorizeUrl").asText()).getRawQuery()).get("state");
		String query = "?code=code-2b8e40d6a1&state=" + enc(state);

		HttpResponse<String> urlCallback = send(request("/auth/callback;jsessionid=" + loginSession + query, null, false));
		assertEquals(400, urlCallback.statusCode());
		assertEquals(NO_PENDING, urlCallback.body());
		assertEquals(0, STUB.at("/token").size());

		// The browser with the cookie completes the sign-in, so the session of the URL held a pending sign-in.
		HttpResponse<String> callback = send(request("/auth/callback" + query, loginSession, false));
		assertEquals(302, callback.statusCode(), callback.body());
		String session = sessionId(sessionCookie(callback));

		HttpResponse<String> urlStatus = send(request("/auth/status;jsessionid=" + session + "?server=" + enc("/fhir"), null, true));
		assertEquals(200, urlStatus.statusCode(), urlStatus.body());
		assertFalse(json(urlStatus).get("signedIn").asBoolean());
		assertTrue(json(status("/fhir", session, true)).get("signedIn").asBoolean());
	}

	private HttpResponse<String> status(String server, String session, boolean proof) throws Exception {
		return send(request("/auth/status?server=" + enc(server), session, proof));
	}

	// The save changes the session id, so the caller continues with the id that this method returns.
	private String save(String server, String session) throws Exception {
		HttpResponse<String> response = post("/auth/client", session, Map.of("server", server, "clientId", CLIENT_ID,
				"clientSecret", CLIENT_SECRET, "scope", SCOPE));
		assertEquals(204, response.statusCode(), response.body());
		return sessionId(sessionCookie(response));
	}

	// The app sends the endpoints of the status that the dialog shows, so each sign-in reads the status first.
	private HttpResponse<String> login(String server, String session) throws Exception {
		JsonNode status = json(status(server, session, true));
		return login(server, session, status.get("authorizationEndpoint").asText(), status.get("tokenEndpoint").asText());
	}

	private HttpResponse<String> login(String server, String session, String authorizationEndpoint, String tokenEndpoint)
			throws Exception {
		return post("/auth/login", session, Map.of("server", server, "authorizationEndpoint", authorizationEndpoint,
				"tokenEndpoint", tokenEndpoint));
	}

	// The app sends the UDAP endpoints of the status that the dialog shows.
	private HttpResponse<String> udapLogin(String server, String session) throws Exception {
		HttpResponse<String> status = status(server, session, true);
		assertEquals(200, status.statusCode(), status.body());
		JsonNode udap = json(status).get("udap");
		return post("/auth/login", session, Map.of("server", server,
				"authorizationEndpoint", udap.get("authorizationEndpoint").asText(),
				"tokenEndpoint", udap.get("tokenEndpoint").asText(),
				"registrationEndpoint", udap.get("registrationEndpoint").asText()));
	}

	private HttpResponse<String> post(String target, String session, Map<String, ?> body) throws Exception {
		return send(request(target, session, true)
				.header("Origin", origin())
				.header("Content-Type", "application/json")
				.POST(BodyPublishers.ofString(JSON.writeValueAsString(body))));
	}

	// The proof headers are the headers that the app sends to its own origin.
	private HttpRequest.Builder request(String target, String session, boolean proof) {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(origin() + target));
		if (session != null) {
			request.header("Cookie", "JSESSIONID=" + session);
		}
		if (proof) {
			request.header("X-App-Request", "1").header("Sec-Fetch-Site", "same-origin");
		}
		return request;
	}

	private String origin() {
		return "http://localhost:" + port;
	}

	private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
		return HTTP.send(request.build(), BodyHandlers.ofString());
	}

	private static JsonNode json(HttpResponse<String> response) throws IOException {
		return JSON.readTree(response.body());
	}

	private static String sessionCookie(HttpResponse<String> response) {
		Optional<String> cookie = response.headers().allValues("Set-Cookie").stream()
				.filter(value -> value.startsWith("JSESSIONID="))
				.findFirst();
		assertTrue(cookie.isPresent(), "no session cookie");
		return cookie.get();
	}

	private static String sessionId(String setCookie) {
		return setCookie.substring("JSESSIONID=".length(), setCookie.indexOf(';'));
	}

	private static Map<String, String> parameters(String encoded) {
		Map<String, String> out = new LinkedHashMap<>();
		URLUtils.parseParameters(encoded).forEach((name, values) -> out.put(name, values.get(0)));
		return out;
	}

	// The same encoding as encodeURIComponent in the frontend, which writes a space as %20.
	private static String enc(String value) {
		return URLEncoder.encode(value, UTF_8).replace("+", "%20");
	}

	private static String discovery() {
		String as = STUB.base();
		return "{\"issuer\":\"" + as + "\",\"authorization_endpoint\":\"" + as + "/authorize\",\"token_endpoint\":\"" + as
				+ "/token\",\"jwks_uri\":\"" + as + "/jwks\",\"capabilities\":[\"launch-standalone\","
				+ "\"client-confidential-symmetric\"]}";
	}

	// This document has no issuer, so the server reads the fhirUser claim of the ID token with no signature check.
	// The token response has an ID token that no key set of the stub verifies.
	private static String listedDiscovery() {
		String as = STUB.base();
		return "{\"authorization_endpoint\":\"" + as + "/authorize\",\"token_endpoint\":\"" + as + "/listed-token\"}";
	}

	private static String listedTokenResponse() {
		return "{\"access_token\":\"" + LISTED_ACCESS_TOKEN + "\",\"token_type\":\"Bearer\",\"expires_in\":3600,"
				+ "\"id_token\":\"" + idToken(UNKNOWN_KEY, "https://unknown.example", CLIENT_ID, "Patient/456") + "\"}";
	}

	private static String tokenResponse() {
		return "{\"access_token\":\"" + ACCESS_TOKEN + "\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\""
				+ GRANTED_SCOPE + "\",\"patient\":\"123\",\"id_token\":\"" + idToken(KEY, STUB.base(), CLIENT_ID, "Patient/123")
				+ "\"}";
	}

	// The signed claims have the three endpoints of the plain JSON, and iss is the FHIR base URL of the entry.
	private static String udapMetadata() {
		String udap = STUB.base() + "/udap";
		Instant now = Instant.now();
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.issuer(udap + "/fhir")
				.subject(udap + "/fhir")
				.issueTime(Date.from(now))
				.expirationTime(Date.from(now.plusSeconds(60)))
				.jwtID(UUID.randomUUID().toString())
				.claim("authorization_endpoint", udap + "/authorize")
				.claim("token_endpoint", udap + "/token")
				.claim("registration_endpoint", udap + "/register")
				.build();
		try {
			SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
					.x509CertChain(List.of(com.nimbusds.jose.util.Base64.encode(UDAP_KEY.getCertificate().getEncoded())))
					.build(), claims);
			signed.sign(new RSASSASigner(UDAP_KEY.getPrivateKey()));
			return "{\"authorization_endpoint\":\"" + udap + "/authorize\",\"token_endpoint\":\"" + udap
					+ "/token\",\"registration_endpoint\":\"" + udap + "/register\",\"grant_types_supported\":"
					+ "[\"authorization_code\"],\"scopes_supported\":[\"openid\",\"fhirUser\",\"launch/patient\","
					+ "\"patient/*.rs\",\"system/*.rs\",\"user/*.rs\"],\"signed_metadata\":\"" + signed.serialize() + "\"}";
		} catch (JOSEException | CertificateEncodingException e) {
			throw new IllegalStateException(e);
		}
	}

	// An issuer and a key set, and no endpoints.
	private static String udapDiscovery() {
		return "{\"issuer\":\"" + STUB.base() + "\",\"jwks_uri\":\"" + STUB.base() + "/jwks\"}";
	}

	// The scope is longer than the scope of the software statement and in a different order, and the answer has no
	// redirect_uris.
	private static String udapRegistration() {
		return "{\"client_id\":\"" + UDAP_CLIENT_ID + "\",\"scope\":\"patient/Observation.rs user/*.rs patient/*.rs"
				+ " launch/patient fhirUser openid\"}";
	}

	private static String udapTokenResponse() {
		return "{\"access_token\":\"" + UDAP_ACCESS_TOKEN + "\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\""
				+ SCOPE + "\",\"patient\":\"123\",\"id_token\":\"" + idToken(KEY, STUB.base(), UDAP_CLIENT_ID, "Patient/123")
				+ "\"}";
	}

	private static String idToken(RSAKey key, String issuer, String audience, String fhirUser) {
		Instant now = Instant.now();
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.issuer(issuer)
				.audience(audience)
				.subject("tester")
				.issueTime(Date.from(now))
				.expirationTime(Date.from(now.plusSeconds(300)))
				.claim("fhirUser", fhirUser)
				.build();
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
		try {
			jwt.sign(new RSASSASigner(key));
		} catch (JOSEException e) {
			throw new IllegalStateException(e);
		}
		return jwt.serialize();
	}

	/** Checks that the key of UDAP_KEY signs the JWT and that the first x5c entry is the certificate of UDAP_KEY. */
	private static JWTClaimsSet signedByUdapKey(String jwt) throws Exception {
		SignedJWT signed = SignedJWT.parse(jwt);
		assertTrue(signed.verify(new RSASSAVerifier((RSAPublicKey) UDAP_KEY.getCertificate().getPublicKey())));
		assertEquals(UDAP_KEY.getCertificate(),
				X509CertUtils.parse(signed.getHeader().getX509CertChain().get(0).decode()));
		return signed.getJWTClaimsSet();
	}

	private static RSAKey rsaKey() {
		try {
			return new RSAKeyGenerator(2048).keyID("auth-login-it").generate();
		} catch (JOSEException e) {
			throw new IllegalStateException(e);
		}
	}

	// No @TestConfiguration: the component scan of the server would add this class to the context of each test.
	static class TestBeans {

		// Only the auth-smart feature serves this document, and this feature does not require auth-smart.
		@Bean
		FilterRegistrationBean<Filter> ownDiscovery() {
			FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
				response.setContentType("application/json");
				response.getOutputStream().write(discovery().getBytes(UTF_8));
			});
			registration.addUrlPatterns("/fhir/.well-known/smart-configuration");
			registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
			return registration;
		}

		// auth-udap serves this document. This feature does not require auth-udap, so the read of the own server
		// gets 404 here and does not reach HAPI.
		@Bean
		FilterRegistrationBean<Filter> ownUdapMetadata() {
			FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(
					(request, response, chain) -> ((HttpServletResponse) response).setStatus(404));
			registration.addUrlPatterns("/fhir/.well-known/udap");
			registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
			return registration;
		}

		// The bean that auth-udap publishes, with the key of this test.
		@Bean("udapCertificate")
		KeyStore.PrivateKeyEntry udapCertificate() {
			return UDAP_KEY;
		}

		@Bean
		HapiRecorder hapiRecorder(RestfulServer server) {
			HapiRecorder recorder = new HapiRecorder();
			server.registerInterceptor(recorder);
			return recorder;
		}
	}

	public static class HapiRecorder {

		@Hook(Pointcut.SERVER_INCOMING_REQUEST_POST_PROCESSED)
		public boolean record(RequestDetails details) {
			String authorization = details.getHeader("Authorization");
			HAPI_AUTHORIZATION.add(authorization == null ? NO_HEADER : authorization);
			return true;
		}
	}

	private record Received(String path, Headers headers, String body) {}

	/**
	 * A local HTTP server on 127.0.0.1 that records each request. The path selects the answer: the two token
	 * endpoints and the key set of the authorization server, the SMART configuration and a search of the listed
	 * server, the UDAP metadata, the SMART configuration, the registration, the token endpoint, and a search of
	 * the UDAP server, an HTML page with status 200, and 404 for each other path.
	 */
	private static final class Stub {

		final List<Received> received = new CopyOnWriteArrayList<>();
		private final HttpServer server;

		Stub() {
			try {
				server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			server.createContext("/", this::handle);
			server.start();
		}

		String base() {
			return "http://127.0.0.1:" + server.getAddress().getPort();
		}

		List<Received> at(String path) {
			return received.stream().filter(request -> request.path().equals(path)).toList();
		}

		void stop() {
			server.stop(0);
		}

		private void handle(HttpExchange exchange) throws IOException {
			try {
				String path = exchange.getRequestURI().getRawPath();
				received.add(new Received(path, exchange.getRequestHeaders(),
						new String(exchange.getRequestBody().readAllBytes(), UTF_8)));
				switch (path) {
					case "/token" -> respond(exchange, 200, "application/json", tokenResponse());
					case "/listed-token" -> respond(exchange, 200, "application/json", listedTokenResponse());
					case "/listed/fhir/.well-known/smart-configuration" -> respond(exchange, 200, "application/json", listedDiscovery());
					case "/listed/fhir/Patient" -> respond(exchange, 200, "application/fhir+json", "{\"resourceType\":\"Bundle\"}");
					case "/jwks" -> respond(exchange, 200, "application/json", new JWKSet(KEY.toPublicJWK()).toString());
					case "/udap/fhir/.well-known/udap" -> respond(exchange, 200, "application/json", udapMetadata());
					case "/udap/fhir/.well-known/smart-configuration" -> respond(exchange, 200, "application/json", udapDiscovery());
					case "/udap/register" -> respond(exchange, 201, "application/json", udapRegistration());
					case "/udap/token" -> respond(exchange, 200, "application/json", udapTokenResponse());
					case "/udap/fhir/Patient" -> respond(exchange, 200, "application/fhir+json", "{\"resourceType\":\"Bundle\"}");
					case "/html/fhir/.well-known/smart-configuration" ->
							respond(exchange, 200, "text/html", "<!doctype html><html><body>FHIR server</body></html>");
					default -> respond(exchange, 404, "application/json", "{}");
				}
			} finally {
				exchange.close();
			}
		}

		private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
			byte[] bytes = body.getBytes(UTF_8);
			exchange.getResponseHeaders().add("Content-Type", contentType);
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
		}
	}
}
