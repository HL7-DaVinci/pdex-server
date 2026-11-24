package com.lantanagroup.pdex.login;

import com.lantanagroup.pdex.login.LoginClients.Kind;
import com.lantanagroup.pdex.login.LoginSession.TokenSet;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginFilterTest {

	private static final String LISTED = "https://listed.example/fhir";

	@Test
	void addsTheOwnTokenOnFhirWithProof() throws ServletException, IOException {
		for (String site : new String[] {"same-origin", null}) {
			MockHttpServletRequest request = fhir(signedIn(own(null)));
			if (site != null) {
				request.addHeader("Sec-Fetch-Site", site);
			}
			HttpServletRequest seen = filter(request);
			assertEquals("Bearer at-1", seen.getHeader("authorization"), site);
			assertEquals(List.of("Bearer at-1"), Collections.list(seen.getHeaders("AUTHORIZATION")), site);
			assertTrue(Collections.list(seen.getHeaderNames())
					.containsAll(List.of(LoginFilter.APP_HEADER, HttpHeaders.AUTHORIZATION)), site);
		}
	}

	@Test
	void leavesTheRequestWithNoProofNoSessionOrItsOwnAuthorization() throws ServletException, IOException {
		MockHttpSession session = signedIn(own(null));
		MockHttpServletRequest noAppHeader = fhir(session);
		noAppHeader.removeHeader(LoginFilter.APP_HEADER);
		MockHttpServletRequest crossSite = fhir(session);
		crossSite.addHeader("Sec-Fetch-Site", "cross-site");
		MockHttpServletRequest noSession = fhir(null);
		MockHttpServletRequest typed = fhir(session);
		typed.addHeader(HttpHeaders.AUTHORIZATION, "Bearer typed");
		for (MockHttpServletRequest request : List.of(noAppHeader, crossSite, noSession, typed)) {
			assertSame(request, filter(request));
		}
		assertNull(noSession.getSession(false));
	}

	@Test
	void proxyDestinationGetsOnlyTheTokenOfItsServer() throws ServletException, IOException {
		MockHttpSession session = signedIn(
				new TokenSet(Kind.LISTED, URI.create(LISTED), "at-listed", null, "user/*.rs", null, null, false));
		assertEquals("Bearer at-listed", filter(proxy(session, url(LISTED + "/Patient"))).getHeader("Authorization"));
		for (String query : List.of(url("https://listed.example/fhir2/Patient"), url("https://other.example/fhir/Patient"),
				"url=%zz")) {
			assertNull(filter(proxy(session, query)).getHeader("Authorization"), query);
		}
		MockHttpServletRequest form = proxy(session, url("https://other.example/fhir/Patient"));
		form.setMethod("POST");
		form.setContentType("application/x-www-form-urlencoded");
		byte[] body = url(LISTED + "/Patient").getBytes(StandardCharsets.UTF_8);
		form.setContent(body);
		// A container reads the parameters from a form body. The mock does not, so the test sets the body value.
		form.addParameter("url", LISTED + "/Patient");
		HttpServletRequest seen = filter(form);
		assertNull(seen.getHeader("Authorization"));
		assertArrayEquals(body, seen.getInputStream().readAllBytes());
	}

	@Test
	void expiredTokenSetIsRemovedAtTheNextUse() throws ServletException, IOException {
		MockHttpSession session = signedIn(own(Instant.now().minusSeconds(1)));
		assertNull(filter(fhir(session)).getHeader("Authorization"));
		assertTrue(LoginSession.find(session).orElseThrow().own().isEmpty());
	}

	private static TokenSet own(Instant expiresAt) {
		return new TokenSet(Kind.OWN, URI.create("http://app.example/fhir"), "at-1", expiresAt, "user/*.rs", null, null,
				false);
	}

	private static MockHttpSession signedIn(TokenSet set) {
		MockHttpSession session = new MockHttpSession();
		LoginSession.of(session).put(set);
		return session;
	}

	// HAPI serves /fhir/*, so the servlet path of a FHIR request is /fhir.
	private static MockHttpServletRequest fhir(MockHttpSession session) {
		return fromApp(session, "/fhir", "/Patient", null);
	}

	private static MockHttpServletRequest proxy(MockHttpSession session, String query) {
		return fromApp(session, "/proxy", null, query);
	}

	private static MockHttpServletRequest fromApp(MockHttpSession session, String servletPath, String pathInfo,
			String query) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", servletPath + (pathInfo == null ? "" : pathInfo));
		request.setServletPath(servletPath);
		request.setPathInfo(pathInfo);
		request.setQueryString(query);
		request.setSession(session);
		request.addHeader(LoginFilter.APP_HEADER, "1");
		return request;
	}

	private static HttpServletRequest filter(MockHttpServletRequest request) throws ServletException, IOException {
		MockFilterChain chain = new MockFilterChain();
		new LoginFilter().doFilter(request, new MockHttpServletResponse(), chain);
		return (HttpServletRequest) chain.getRequest();
	}

	private static String url(String destination) {
		return "url=" + URLEncoder.encode(destination, StandardCharsets.UTF_8);
	}
}
