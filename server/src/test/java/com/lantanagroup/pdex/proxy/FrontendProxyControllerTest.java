package com.lantanagroup.pdex.proxy;

import com.lantanagroup.pdex.config.FhirServers;
import com.lantanagroup.pdex.proxy.PublicAddresses.BlockedAddressException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.ResourceAccessException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The responses that the proxy writes itself, before it opens a connection to an upstream server.
 */
class FrontendProxyControllerTest {

	private static final String LISTED = "https://listed.example/fhir";

	@Test
	void returns404WhenDisabled() throws IOException {
		assertEquals(404, send(controller(false, true), "GET", null).getStatus());
		assertEquals(404, send(controller(false, true), "GET", url(LISTED + "/Patient")).getStatus());
	}

	@Test
	void returns405ForOtherMethods() throws IOException {
		for (String method : List.of("OPTIONS", "TRACE")) {
			MockHttpServletResponse response = send(controller(true, true), method, url(LISTED + "/Patient"));
			assertEquals(405, response.getStatus(), method);
			assertEquals("GET, HEAD, POST, PUT, PATCH, DELETE", response.getHeader("Allow"), method);
		}
	}

	@Test
	void returns400ForMissingOrInvalidUrl() throws IOException {
		for (String query : new String[] {null, "other=1", url("ftp://a.example/x"), "url=%zz"}) {
			assertEquals(400, send(controller(true, true), "GET", query).getStatus(), query);
		}
	}

	@Test
	void returns403ForUnlistedWhenPassthroughOff() throws IOException {
		assertEquals(403, send(controller(true, false), "GET", url("https://other.example/fhir/Patient")).getStatus());
		// The host of a listed server, outside its base path.
		assertEquals(403, send(controller(true, false), "GET", url("https://listed.example/admin")).getStatus());
	}

	@Test
	void rootRelativeEntriesAreNeverListedBases() throws IOException {
		// java.net.URI refuses the raw space in "/my fhir", so the controller starts only when it skips each
		// relative entry before it builds a base URI.
		FrontendProxyController controller = controller(true, false, "[{\"name\":\"Local\",\"url\":\"/fhir\"},"
				+ "{\"name\":\"Spaced\",\"url\":\"/my fhir\"},{\"name\":\"Listed\",\"url\":\"" + LISTED + "\"}]");
		for (String destination : List.of("https://other.example/fhir/Patient", "http://localhost/fhir/Patient",
				"https://listed.example/admin")) {
			assertEquals(403, send(controller, "GET", url(destination)).getStatus(), destination);
		}
	}

	@Test
	void everyOwnResponseHasHardeningHeaders() throws IOException {
		for (MockHttpServletResponse response : ownErrors()) {
			assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
			assertEquals("sandbox", response.getHeader("Content-Security-Policy"));
		}
	}

	@Test
	void ownErrorsArePlainTextWithAReason() throws IOException {
		for (MockHttpServletResponse response : ownErrors()) {
			String status = String.valueOf(response.getStatus());
			assertTrue(response.getContentType().startsWith("text/plain"), status);
			assertFalse(response.getContentAsString().isBlank(), status);
			// sendError commits the response and records an error message.
			assertFalse(response.isCommitted(), status);
			assertNull(response.getErrorMessage(), status);
		}
	}

	@Test
	void refusesBodyThatAReaderTook() throws IOException {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/proxy");
		request.setQueryString(url(LISTED + "/Patient"));
		request.setContent("{\"resourceType\":\"Patient\"}".getBytes(StandardCharsets.UTF_8));
		// A filter that called getReader takes the body, and getInputStream then throws.
		request.getReader();
		MockHttpServletResponse response = new MockHttpServletResponse();
		controller(true, true).proxy(request, response);
		assertEquals(400, response.getStatus());
		assertEquals(FrontendProxyController.INCOMPLETE_BODY, response.getContentAsString());
		assertTrue(response.getContentType().startsWith("text/plain"));
		assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
		assertEquals("sandbox", response.getHeader("Content-Security-Policy"));
		assertNull(response.getErrorMessage());
	}

	@Test
	void mapsUpstreamFailuresToStatus() throws IOException {
		InetAddress loopback = InetAddress.getByName("127.0.0.1");
		assertEquals(403, FrontendProxyController.statusFor(failure(new BlockedAddressException("internal", loopback))));
		assertEquals(504, FrontendProxyController.statusFor(failure(new ConnectTimeoutException("connect"))));
		assertEquals(504, FrontendProxyController.statusFor(failure(new SocketTimeoutException("read"))));
		assertEquals(502, FrontendProxyController.statusFor(failure(new ConnectException("refused"))));
		assertEquals(502, FrontendProxyController.statusFor(failure(new UnknownHostException("nowhere.example"))));
		assertEquals(502, FrontendProxyController.statusFor(new IllegalArgumentException("not sent")));
	}

	private static List<MockHttpServletResponse> ownErrors() throws IOException {
		List<MockHttpServletResponse> responses = List.of(
				send(controller(false, true), "GET", url(LISTED + "/Patient")),
				send(controller(true, true), "TRACE", url(LISTED + "/Patient")),
				send(controller(true, true), "GET", null),
				send(controller(true, false), "GET", url("https://other.example/fhir")));
		assertEquals(List.of(404, 405, 400, 403), responses.stream().map(MockHttpServletResponse::getStatus).toList());
		return responses;
	}

	private static FrontendProxyController controller(boolean enabled, boolean passthrough) {
		return controller(enabled, passthrough, "[{\"name\":\"Listed\",\"url\":\"" + LISTED + "\"}]");
	}

	private static FrontendProxyController controller(boolean enabled, boolean passthrough, String fhirServers) {
		FhirServers servers = new FhirServers(new MockEnvironment().withProperty("app.fhir.servers", fhirServers));
		return new FrontendProxyController(servers, ClientHttpRequestFactorySettings.defaults(), enabled, passthrough,
				Duration.ofSeconds(5), Duration.ofSeconds(60));
	}

	private static MockHttpServletResponse send(FrontendProxyController controller, String method, String query)
			throws IOException {
		MockHttpServletRequest request = new MockHttpServletRequest(method, "/proxy");
		request.setQueryString(query);
		MockHttpServletResponse response = new MockHttpServletResponse();
		controller.proxy(request, response);
		return response;
	}

	// The same encoding as encodeURIComponent in the frontend, which writes a space as %20.
	private static String url(String destination) {
		return "url=" + URLEncoder.encode(destination, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static ResourceAccessException failure(IOException cause) {
		return new ResourceAccessException("I/O error", cause);
	}
}
