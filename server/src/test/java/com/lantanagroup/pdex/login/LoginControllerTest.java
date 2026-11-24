package com.lantanagroup.pdex.login;

import com.lantanagroup.pdex.login.LoginClients.Discovery;
import com.lantanagroup.pdex.login.LoginClients.Kind;
import com.lantanagroup.pdex.login.LoginClients.PlainHttpException;
import com.lantanagroup.pdex.login.LoginClients.Target;
import com.lantanagroup.pdex.login.LoginSession.ClientAuth;
import com.lantanagroup.pdex.login.LoginSession.Pending;
import com.nimbusds.common.contenttype.ContentType;
import com.nimbusds.oauth2.sdk.AuthorizationRequest;
import com.nimbusds.oauth2.sdk.http.HTTPRequest;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginControllerTest {

	private static final String OWN = "/fhir";
	private static final Target TARGET = new Target(Kind.OWN, URI.create("http://app.example/fhir"),
			URI.create("http://127.0.0.1:8080/fhir"));
	private static final Discovery DISCOVERY = new Discovery(URI.create("https://as.example/authorize"),
			"https://as.example/authorize", URI.create("https://as.example/token"), "https://as.example/token", List.of(),
			null, null);

	private final LoginClients clients = mock(LoginClients.class);
	private final LoginController controller = new LoginController(clients, mock(IdTokenCheck.class),
			mock(UdapClient.class), true);
	private final MockHttpSession session = new MockHttpSession();

	@BeforeEach
	void ownServerHasADiscoveryDocument() throws Exception {
		when(clients.target(eq(OWN), any(), anyInt())).thenReturn(TARGET);
		when(clients.discover(TARGET)).thenReturn(DISCOVERY);
	}

	@Test
	void callbackWithNoPendingSignInOrAWrongStateAnswers400() throws Exception {
		assertEquals(400, controller.callback(new MockHttpServletRequest(), "s", "code-1", null).getStatusCode().value());
		LoginSession.of(session);
		assertEquals(400, callback("s", "code-1", null).getStatusCode().value());
		start(ClientAuth.NONE);
		assertEquals(400, callback(null, "code-1", null).getStatusCode().value());
		String state = start(ClientAuth.NONE);
		assertEquals(400, callback("other", "code-1", null).getStatusCode().value());
		verify(clients, never()).send(any(), any());
		when(clients.send(any(), any())).thenReturn(json(200, "{\"access_token\":\"at-1\",\"token_type\":\"Bearer\"}"));
		assertHome(callback(state, "code-1", null));
	}

	@Test
	void errorsAreKeptOneTimeWithTheirCodeOnlyWhenItIsValid() throws Exception {
		assertHome(callback(start(ClientAuth.NONE), null, "access_denied"));
		assertTrue(((String) status().get("error")).contains("access_denied"));
		assertFalse(status().containsKey("error"));

		assertHome(callback(start(ClientAuth.NONE), null, "bad\"code"));
		String error = (String) status().get("error");
		assertNotNull(error);
		assertFalse(error.contains("bad"), error);

		when(clients.send(any(), any())).thenReturn(json(400, "{\"error\":\"invalid_grant\"}"));
		assertHome(callback(start(ClientAuth.BASIC), "code-1", null));
		assertTrue(((String) status().get("error")).contains("invalid_grant"));
	}

	@Test
	void tokenTypeOtherThanBearerFailsTheSignIn() throws Exception {
		when(clients.send(any(), any()))
				.thenReturn(json(200, "{\"access_token\":\"at-1\",\"token_type\":\"DPoP\",\"expires_in\":60}"));
		assertHome(callback(start(ClientAuth.NONE), "code-1", null));
		Map<String, Object> status = status();
		assertEquals(false, status.get("signedIn"));
		assertNotNull(status.get("error"));
	}

	@Test
	void secondCallbackSendsNoSecondTokenCall() throws Exception {
		when(clients.send(any(), any())).thenReturn(json(200, "{\"access_token\":\"at-1\",\"token_type\":\"Bearer\"}"));
		String state = start(ClientAuth.NONE);
		assertHome(callback(state, "code-1", null));
		Map<String, Object> status = status();
		assertEquals(true, status.get("signedIn"));
		assertFalse(status.containsKey("expiresAt"));

		assertEquals(400, callback(state, "code-1", null).getStatusCode().value());
		ArgumentCaptor<HTTPRequest> sent = ArgumentCaptor.forClass(HTTPRequest.class);
		verify(clients, times(1)).send(eq(Kind.OWN), sent.capture());
		assertTrue(sent.getValue().getBody().contains("client_id=client-1"), sent.getValue().getBody());
		assertNull(sent.getValue().getAuthorization());
	}

	@Test
	void signInUsesSavedClientValuesAndAnEmptySecretKeepsTheSecretOfTheSameClientId() throws Exception {
		Map<String, String> signIn = Map.of("server", OWN, "authorizationEndpoint", DISCOVERY.authorizePublished(),
				"tokenEndpoint", DISCOVERY.tokenPublished());
		ResponseEntity<Object> refused = controller.login(withOrigin(), signIn);
		assertEquals(400, refused.getStatusCode().value());
		assertTrue(((String) refused.getBody()).contains("no client values"), (String) refused.getBody());
		verify(clients, never()).discover(any());

		assertEquals(204, save("client-1", "s3cret", "openid patient/*.rs"));
		assertEquals(204, save("client-1", "", "openid launch/patient"));
		Map<String, Object> status = status();
		assertEquals(Map.of("method", "client", "clientId", "client-1", "scope", "openid launch/patient", "hasSecret",
				true), status.get("client"));
		assertFalse(status.toString().contains("s3cret"), status.toString());
		ResponseEntity<Object> started = controller.login(withOrigin(), signIn);
		assertEquals(200, started.getStatusCode().value());
		State state = AuthorizationRequest.parse(URI.create((String) ((Map<?, ?>) started.getBody()).get("authorizeUrl")))
				.getState();
		Pending pending = LoginSession.of(session).takePending(state.getValue());
		assertEquals(ClientAuth.BASIC, pending.clientAuth());
		assertEquals("s3cret", pending.secret());
		assertEquals("openid launch/patient", pending.scope());

		assertEquals(204, controller.saveClient(fromApp(), Map.of("server", OWN, "method", "udap", "scope",
				"openid launch/patient")).getStatusCode().value());
		assertEquals(Map.of("method", "udap", "scope", "openid launch/patient"), status().get("client"));
		assertEquals(204, save("client-1", "", "openid launch/patient"));
		assertEquals(true, ((Map<?, ?>) status().get("client")).get("hasSecret"));
		assertEquals(204, save("client-2", "", "openid launch/patient"));
		assertEquals(false, ((Map<?, ?>) status().get("client")).get("hasSecret"));
	}

	@Test
	void plainHttpRefusalIsAStatusWithItsReason() throws Exception {
		String server = "http://fhir.example/fhir";
		PlainHttpException refusal = new PlainHttpException();
		when(clients.target(eq(server), any(), anyInt())).thenThrow(refusal);
		ResponseEntity<Object> response = controller.status(fromApp(), server);
		assertEquals(200, response.getStatusCode().value());
		assertEquals(Map.of("offered", false, "reason", "The server URL uses plain http. " + refusal.getMessage(),
				"plainHttp", true, "signedIn", false), response.getBody());

		doThrow(refusal).when(clients).discover(TARGET);
		assertEquals(true, status().get("plainHttp"));
	}

	private String start(ClientAuth clientAuth) {
		State state = new State();
		LoginSession.of(session).start(new Pending(OWN, TARGET, "client-1", clientAuth,
				clientAuth == ClientAuth.BASIC ? "s+e/c=r%e:t" : "", "openid patient/*.rs",
				URI.create("http://app.example/auth/callback"), state, new CodeVerifier(), DISCOVERY));
		return state.getValue();
	}

	private int save(String clientId, String secret, String scope) {
		return controller.saveClient(fromApp(), Map.of("server", OWN, "clientId", clientId, "clientSecret", secret,
				"scope", scope)).getStatusCode().value();
	}

	private ResponseEntity<Object> callback(String state, String code, String error) {
		return controller.callback(fromApp(), state, code, error);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> status() {
		ResponseEntity<Object> response = controller.status(fromApp(), OWN);
		assertEquals(200, response.getStatusCode().value());
		return (Map<String, Object>) response.getBody();
	}

	private MockHttpServletRequest fromApp() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setSession(session);
		request.addHeader(LoginFilter.APP_HEADER, "1");
		return request;
	}

	// The sign-in start needs an Origin that passes the http rule, because the callback URL is on that origin.
	private MockHttpServletRequest withOrigin() {
		MockHttpServletRequest request = fromApp();
		request.addHeader(HttpHeaders.ORIGIN, "http://localhost:5173");
		return request;
	}

	private static void assertHome(ResponseEntity<Object> response) {
		assertEquals(302, response.getStatusCode().value());
		assertEquals("/", response.getHeaders().getFirst(HttpHeaders.LOCATION));
	}

	private static HTTPResponse json(int status, String body) {
		HTTPResponse response = new HTTPResponse(status);
		response.setEntityContentType(ContentType.APPLICATION_JSON);
		response.setBody(body);
		return response;
	}
}
