package com.lantanagroup.pdex.proxy;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ca.uhn.fhir.jpa.starter.Application;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.Filter;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots the full server with the proxy and passthrough on, and sends requests through /proxy to two JDK
 * HTTP stubs. The listed stub is a listed server in app.fhir.servers. The unlisted stub is not, so a request
 * to it is a passthrough request that the guard must refuse.
 *
 * <p>The requests use java.net.http.HttpClient, which sends Origin and PATCH as given. A request that must
 * pass sends the origin of the application as Origin, because the CORS mapping for /proxy refuses every
 * other origin. The test sets hapi.fhir.cors to the widest allowance, so each cross-origin refusal proves the
 * mapping for /proxy.
 */
@ActiveProfiles("test")
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		classes = {Application.class},
		properties = {
			"spring.datasource.url=jdbc:h2:mem:frontendproxyit",
			// Sets the FHIR version of the server project. The application-test.yaml of the starter has R4.
			"hapi.fhir.fhir_version=R4",
			"app.proxy.enabled=true",
			"app.proxy.passthrough=true",
			"app.proxy.read-timeout=2s",
			"hapi.fhir.cors.allowed_origin[0]=*",
			"spring.ai.mcp.server.enabled=false",
			"spring.main.allow-bean-definition-overriding=true",
			"management.health.elasticsearch.enabled=false",
			"spring.jpa.properties.hibernate.search.backend.directory.type=local-heap"
		})
@Import(FrontendProxyControllerIT.ShortBodyFilterConfiguration.class)
class FrontendProxyControllerIT {

	private static final byte[] GZIP_BODY = gzip("{\"resourceType\":\"Bundle\",\"type\":\"searchset\"}");
	private static final byte[] FIRST_CHUNK = "first chunk ".getBytes(UTF_8);
	private static final byte[] SECOND_CHUNK = "second chunk".getBytes(UTF_8);
	// Shorter than the Content-Length of the truncated path by less than the size of an error page.
	private static final byte[] TRUNCATED_BODY = "x".repeat(990).getBytes(UTF_8);
	private static final int TRUNCATED_LENGTH = 1000;
	private static final String SHORT_BODY = "X-Test-Short-Body";
	private static final String EVIL_ORIGIN = "https://evil.example";

	private static final Stub LISTED;
	private static final Stub UNLISTED;

	static {
		LISTED = new Stub();
		UNLISTED = new Stub();
	}

	private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void fhirServers(DynamicPropertyRegistry registry) {
		registry.add("app.fhir.servers", () -> "[{\"name\":\"Listed\",\"url\":\"" + LISTED.base() + "/fhir\",\"secret\":\"s3\"},"
				+ "{\"name\":\"Closed port\",\"url\":\"http://localhost:1/fhir\"},{\"name\":\"Local\",\"url\":\"/fhir\"}]");
	}

	@BeforeEach
	void clearStubs() {
		LISTED.received.clear();
		UNLISTED.received.clear();
	}

	@AfterAll
	static void stopStubs() {
		LISTED.stop();
		UNLISTED.stop();
	}

	@Test
	void forwardsMethodPathQueryAndBody() throws Exception {
		HttpResponse<String> read = send(HttpRequest.newBuilder(proxy(LISTED.base() + "/fhir/Patient?name=Smith&_count=2")));
		assertEquals(200, read.statusCode());
		assertEquals("GET /fhir/Patient", read.body());

		Received get = LISTED.only();
		assertEquals("GET", get.method());
		assertEquals("/fhir/Patient", get.path());
		assertEquals("name=Smith&_count=2", get.query());
		assertEquals(0, get.body().length);

		// A browser sends Origin with each of these methods.
		record Write(String method, String path, String contentType, String body) {}
		String patient = "{\"resourceType\":\"Patient\",\"id\":\"1\"}";
		List<Write> writes = List.of(
				new Write("POST", "/fhir/Patient", "application/fhir+json", patient),
				new Write("PUT", "/fhir/Patient/1", "application/fhir+json", patient),
				new Write("PATCH", "/fhir/Patient/1", "application/json-patch+json", "[{\"op\":\"replace\",\"path\":\"/active\",\"value\":true}]"),
				new Write("DELETE", "/fhir/Patient/1", null, ""));
		for (Write write : writes) {
			HttpRequest.Builder request = HttpRequest.newBuilder(proxy(LISTED.base() + write.path())).header("Origin", origin());
			if (write.contentType() == null) {
				request.method(write.method(), BodyPublishers.noBody());
			} else {
				request.method(write.method(), BodyPublishers.ofString(write.body())).header("Content-Type", write.contentType());
			}
			HttpResponse<String> response = send(request);
			assertEquals(200, response.statusCode(), write.method());
			assertEquals(write.method() + " " + write.path(), response.body());
			Received received = LISTED.last();
			assertEquals(write.method(), received.method());
			assertEquals(write.path(), received.path());
			assertEquals(write.body(), new String(received.body(), UTF_8), write.method());
			if (write.contentType() != null) {
				assertEquals(write.contentType(), received.headers().getFirst("Content-Type"), write.method());
			}
		}
		assertEquals(1 + writes.size(), LISTED.received.size());
	}

	@Test
	void dropsDeniedRequestHeaders() throws Exception {
		HttpResponse<String> response = send(HttpRequest.newBuilder(proxy(LISTED.base() + "/fhir/metadata"))
				.header("Cookie", "JSESSIONID=browser")
				.header("Origin", origin())
				.header("Referer", origin() + "/patients")
				.header("X-Forwarded-For", "203.0.113.7")
				// Tomcat takes a forwarded host from a loopback address, and the CORS mapping of /proxy compares it with Origin.
				.header("X-Forwarded-Host", "localhost:" + port)
				.header("Forwarded", "for=203.0.113.7")
				.header("Sec-Fetch-Site", "same-origin")
				.header("Sec-Ch-Ua", "\"Chromium\";v=\"140\"")
				.header("X-App-Request", "1")
				.header("Authorization", "Bearer browser-token"));
		assertEquals(200, response.statusCode());
		Headers headers = LISTED.only().headers();
		for (String name : List.of("Cookie", "Origin", "Referer", "X-Forwarded-For", "X-Forwarded-Host", "Forwarded",
				"Sec-Fetch-Site", "Sec-Ch-Ua", "X-App-Request")) {
			assertFalse(headers.containsKey(name), name);
		}
		assertEquals("Bearer browser-token", headers.getFirst("Authorization"));

		// The JDK client always sends User-Agent and sends Proxy-Authorization only to a proxy, so a raw request
		// sends these two cases.
		String raw = rawRequest("GET " + proxyTarget(LISTED.base() + "/fhir/metadata") + " HTTP/1.1\r\n"
				+ "Host: localhost:" + port + "\r\n"
				+ "Proxy-Authorization: Basic cHJveHk6c2VjcmV0\r\n"
				+ "Connection: close\r\n\r\n");
		assertTrue(raw.startsWith("HTTP/1.1 200"), raw);
		Headers rawHeaders = LISTED.last().headers();
		assertFalse(rawHeaders.containsKey("Proxy-Authorization"));
		// The proxy adds no User-Agent of its own.
		assertFalse(rawHeaders.containsKey("User-Agent"), String.valueOf(rawHeaders.get("User-Agent")));
	}

	@Test
	void refusesRequestFromOtherOrigin() throws Exception {
		HttpResponse<String> response = send(HttpRequest.newBuilder(proxy(LISTED.base() + "/fhir/Patient"))
				.POST(BodyPublishers.ofString("{\"resourceType\":\"Patient\"}"))
				.header("Content-Type", "application/fhir+json")
				.header("Origin", EVIL_ORIGIN));
		assertEquals(403, response.statusCode());
		assertEquals(0, LISTED.received.size());
	}

	@Test
	void relaysOnlyAllowedResponseHeaders() throws Exception {
		HttpResponse<String> response = send(HttpRequest.newBuilder(proxy(LISTED.base() + "/fhir/cookie")));
		assertEquals(200, response.statusCode());
		HttpHeaders headers = response.headers();
		assertEquals(Optional.of("\"v1\""), headers.firstValue("ETag"));
		for (String name : List.of("Set-Cookie", "Access-Control-Allow-Origin", "WWW-Authenticate")) {
			assertEquals(Optional.empty(), headers.firstValue(name), name);
		}
		assertEquals(List.of("nosniff"), headers.allValues("X-Content-Type-Options"));
		assertEquals(List.of("sandbox"), headers.allValues("Content-Security-Policy"));

		// The client does not retry a 503 itself, so the browser gets the status and Retry-After unchanged.
		HttpResponse<String> unavailable = send(HttpRequest.newBuilder(proxy(LISTED.base() + "/fhir/unavailable")));
		assertEquals(503, unavailable.statusCode());
		assertEquals(Optional.of("1"), unavailable.headers().firstValue("Retry-After"));
		assertEquals(2, LISTED.received.size());
	}

	@Test
	void doesNotReplayUpstreamCookies() throws Exception {
		assertEquals(200, send(HttpRequest.newBuilder(proxy(LISTED.base() + "/fhir/cookie"))).statusCode());
		assertEquals(200, send(HttpRequest.newBuilder(proxy(LISTED.base() + "/fhir/Patient"))).statusCode());
		assertEquals(2, LISTED.received.size());
		assertFalse(LISTED.received.get(1).headers().containsKey("Cookie"));
	}

	@Test
	void answers502ForUpstreamRedirect() throws Exception {
		HttpResponse<String> response = send(HttpRequest.newBuilder(proxy(LISTED.base() + "/fhir/redirect")));
		assertEquals(502, response.statusCode());
		assertTrue(response.body().contains("302"), response.body());
		assertTrue(response.body().contains("/next"), response.body());
		assertEquals(Optional.of("nosniff"), response.headers().firstValue("X-Content-Type-Options"));
		assertEquals(Optional.of("sandbox"), response.headers().firstValue("Content-Security-Policy"));
		assertEquals(1, LISTED.received.size());
	}

	@Test
	void answers502OnConnectFailure() throws Exception {
		HttpResponse<String> response = send(HttpRequest.newBuilder(proxy("http://localhost:1/fhir/Patient")));
		assertEquals(502, response.statusCode());
		assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
		// The reason is fixed text. An exception message can name an internal host or address.
		assertFalse(response.body().contains("localhost"), response.body());
		assertFalse(response.body().contains("127.0.0.1"), response.body());
	}

	@Test
	void answers504OnReadTimeout() throws Exception {
		HttpResponse<String> response = send(HttpRequest.newBuilder(proxy(LISTED.base() + "/fhir/slow")));
		assertEquals(504, response.statusCode());
		assertEquals(1, LISTED.received.size());
	}

	@Test
	void refusesInternalPassthrough() throws Exception {
		// The IP literals prove that the client asks the guard for a literal too, and connects to no address
		// that the guard did not examine. 0.0.0.0 reaches the local host on Linux.
		for (String host : List.of("localhost", "127.0.0.1", "[::1]", "0.0.0.0")) {
			HttpResponse<String> response = send(HttpRequest.newBuilder(proxy("http://" + host + ":" + UNLISTED.port() + "/fhir/Patient")));
			assertEquals(403, response.statusCode(), host);
			assertEquals(FrontendProxyController.GUARD_REFUSAL, response.body(), host);
		}
		assertEquals(0, UNLISTED.received.size());
		// The unlisted stub answers a direct request, so it received nothing because the guard refused.
		HttpResponse<String> direct = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + UNLISTED.port() + "/fhir/Patient")));
		assertEquals(200, direct.statusCode());
	}

	@Test
	void keepsFhirQueryCharacters() throws Exception {
		String destination = LISTED.base() + "/fhir/Patient?identifier=http://sys|123&name=Smith\\,John&given=Mary Ann";
		assertEquals(200, send(HttpRequest.newBuilder(proxy(destination))).statusCode());
		Map<String, String> query = LISTED.only().decodedQuery();
		assertEquals("http://sys|123", query.get("identifier"));
		assertEquals("Smith\\,John", query.get("name"));
		assertEquals("Mary Ann", query.get("given"));
	}

	@Test
	void relaysBodylessResponses() throws Exception {
		HttpResponse<String> empty = send(HttpRequest.newBuilder(proxy(LISTED.base() + "/fhir/empty")));
		assertEquals(204, empty.statusCode());
		assertEquals("", empty.body());

		HttpResponse<String> head = send(HttpRequest.newBuilder(proxy(LISTED.base() + "/fhir/head"))
				.method("HEAD", BodyPublishers.noBody()));
		assertEquals(200, head.statusCode());
		assertEquals(Optional.of("42"), head.headers().firstValue("Content-Length"));
		assertEquals("HEAD", LISTED.received.get(1).method());
	}

	@Test
	void passesGzipBytesUnchanged() throws Exception {
		HttpResponse<byte[]> response = HTTP.send(HttpRequest.newBuilder(proxy(LISTED.base() + "/fhir/gzip"))
				.header("Accept-Encoding", "gzip")
				.build(), BodyHandlers.ofByteArray());
		assertEquals(200, response.statusCode());
		assertEquals(Optional.of("gzip"), response.headers().firstValue("Content-Encoding"));
		assertArrayEquals(GZIP_BODY, response.body());
		// The upstream gets the Accept-Encoding of the browser, so it picks an encoding that the browser decodes.
		assertEquals(List.of("gzip"), LISTED.only().headers().get("Accept-Encoding"));
	}

	@Test
	void streamsResponseBeforeUpstreamFinishes() throws Exception {
		HttpResponse<InputStream> response = HTTP.send(HttpRequest.newBuilder(proxy(LISTED.base() + "/fhir/stream")).build(),
				BodyHandlers.ofInputStream());
		assertEquals(200, response.statusCode());
		try (InputStream body = response.body()) {
			assertArrayEquals(FIRST_CHUNK, body.readNBytes(FIRST_CHUNK.length));
			long firstChunkArrived = System.nanoTime();
			assertArrayEquals(SECOND_CHUNK, body.readAllBytes());
			assertTrue(firstChunkArrived < LISTED.streamFinished, "the first chunk arrived after the stub finished");
		}
	}

	@Test
	void deniesCrossOriginPreflight() throws Exception {
		HttpResponse<String> preflight = send(HttpRequest.newBuilder(proxy(LISTED.base() + "/fhir/Patient"))
				.method("OPTIONS", BodyPublishers.noBody())
				.header("Origin", EVIL_ORIGIN)
				.header("Access-Control-Request-Method", "GET"));
		assertEquals(Optional.empty(), preflight.headers().firstValue("Access-Control-Allow-Origin"));
		assertEquals(403, preflight.statusCode());

		// The same preflight to a different MVC path gets the allowance of hapi.fhir.cors.
		HttpResponse<String> control = send(HttpRequest.newBuilder(URI.create(origin() + "/config.js"))
				.method("OPTIONS", BodyPublishers.noBody())
				.header("Origin", EVIL_ORIGIN)
				.header("Access-Control-Request-Method", "GET"));
		assertEquals(Optional.of(EVIL_ORIGIN), control.headers().firstValue("Access-Control-Allow-Origin"));

		// An OPTIONS request that is not a preflight reaches the controller, which does not forward it.
		HttpResponse<String> options = send(HttpRequest.newBuilder(proxy(LISTED.base() + "/fhir/Patient"))
				.method("OPTIONS", BodyPublishers.noBody()));
		assertEquals(405, options.statusCode());
		assertEquals(Optional.of("nosniff"), options.headers().firstValue("X-Content-Type-Options"));
		assertEquals(0, LISTED.received.size());
	}

	@Test
	void refusesBodyThatWasAlreadyRead() throws Exception {
		// FormContentFilter of Spring reads a form-encoded PUT, PATCH, or DELETE body, and the multipart resolver
		// reads a multipart body, before the controller runs. The early path answers before it reads a body, so a
		// request head that reached it would show here, and a reused connection would put the next request out
		// of step.
		String form = "family=Smith";
		String boundary = "proxy-it-boundary";
		String multipartBody = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"family\"\r\n\r\nSmith\r\n--"
				+ boundary + "--\r\n";
		List<HttpResponse<String>> refused = List.of(
				send(formRequest("PUT", BodyPublishers.ofString(form))),
				send(formRequest("DELETE", BodyPublishers.ofString(form))),
				// A body publisher with no length sends the body chunked.
				send(formRequest("PUT", BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(form.getBytes(UTF_8))))),
				send(HttpRequest.newBuilder(proxy(LISTED.base() + "/fhir/early"))
						.POST(BodyPublishers.ofString(multipartBody))
						.header("Content-Type", "multipart/form-data; boundary=" + boundary)
						.header("Origin", origin())));
		HttpResponse<String> next = send(HttpRequest.newBuilder(proxy(LISTED.base() + "/fhir/Patient")));
		// Tomcat reads a form-encoded POST body only for getParameter, and nothing on the path to the proxy calls it.
		HttpResponse<String> post = send(HttpRequest.newBuilder(proxy(LISTED.base() + "/fhir/Patient/_search"))
				.POST(BodyPublishers.ofString(form))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.header("Origin", origin()));

		for (HttpResponse<String> response : refused) {
			String request = response.request().method() + " " + response.request().headers().firstValue("Content-Type").orElse("")
					+ " length=" + response.request().bodyPublisher().map(HttpRequest.BodyPublisher::contentLength).orElse(0L);
			assertEquals(400, response.statusCode(), request);
			assertEquals(FrontendProxyController.INCOMPLETE_BODY, response.body(), request);
			assertEquals(Optional.of("nosniff"), response.headers().firstValue("X-Content-Type-Options"), request);
			assertEquals(Optional.of("sandbox"), response.headers().firstValue("Content-Security-Policy"), request);
		}
		// The stub received nothing for the refused requests, not even a head.
		assertEquals(List.of("GET /fhir/Patient", "POST /fhir/Patient/_search"),
				LISTED.received.stream().map(received -> received.method() + " " + received.path()).toList());
		assertEquals(200, next.statusCode(), next.body());
		assertEquals("GET /fhir/Patient", next.body());
		assertEquals(200, post.statusCode(), post.body());
		assertEquals(form, new String(LISTED.last().body(), UTF_8));
	}

	private HttpRequest.Builder formRequest(String method, HttpRequest.BodyPublisher body) {
		return HttpRequest.newBuilder(proxy(LISTED.base() + "/fhir/early"))
				.method(method, body)
				.header("Content-Type", "application/x-www-form-urlencoded")
				.header("Origin", origin());
	}

	@Test
	void refusesBodyThatEndsEarly() throws Exception {
		// The test filter gives the proxy half of this body, and its stream is not finished before the read. The
		// early path answers before it reads a body, so a reused connection would put the next request out of step.
		HttpResponse<String> put = send(HttpRequest.newBuilder(proxy(LISTED.base() + "/fhir/early"))
				.PUT(BodyPublishers.ofString("{\"resourceType\":\"Patient\",\"id\":\"1\"}"))
				.header("Content-Type", "application/fhir+json")
				.header("Origin", origin())
				.header(SHORT_BODY, "true"));
		HttpResponse<String> next = send(HttpRequest.newBuilder(proxy(LISTED.base() + "/fhir/Patient")));
		assertEquals(400, put.statusCode(), put.body());
		assertEquals(FrontendProxyController.INCOMPLETE_BODY, put.body());
		assertEquals(200, next.statusCode(), next.body());
		assertEquals("GET /fhir/Patient", next.body());
		// The head of the refused PUT reached the stub, and the GET came on a different connection.
		Received refused = LISTED.received.stream().filter(r -> "PUT".equals(r.method())).findFirst().orElseThrow();
		Received get = LISTED.received.stream().filter(r -> "GET".equals(r.method())).findFirst().orElseThrow();
		assertNotEquals(refused.port(), get.port());
	}

	@Test
	void detectsTruncatedUpstreamBody() throws Exception {
		// The stub sends part of the body and drops the connection. The client must see an incomplete body, never a
		// complete response with bytes that the upstream did not send.
		for (String path : List.of("/fhir/truncated-chunked", "/fhir/truncated-length")) {
			HttpRequest request = HttpRequest.newBuilder(proxy(LISTED.base() + path)).build();
			try {
				// The limit covers the whole body. A connection that stays open with a short body is a failure.
				HttpResponse<byte[]> response = HTTP.sendAsync(request, BodyHandlers.ofByteArray()).get(15, TimeUnit.SECONDS);
				byte[] body = response.body();
				String tail = new String(Arrays.copyOfRange(body, Math.max(0, body.length - 100), body.length), UTF_8);
				fail(path + ": a complete response with status " + response.statusCode() + " and " + body.length
						+ " bytes, tail: " + tail);
			} catch (TimeoutException e) {
				fail(path + ": the response did not end within 15 seconds");
			} catch (ExecutionException e) {
				// The client sees that the body is incomplete.
				assertInstanceOf(IOException.class, e.getCause(), path);
			}
			// The next request from the same client comes back whole.
			HttpResponse<String> next = send(HttpRequest.newBuilder(proxy(LISTED.base() + "/fhir/Patient")));
			assertEquals(200, next.statusCode(), path);
			assertEquals("GET /fhir/Patient", next.body(), path);
		}
	}

	@Test
	void configJsHasProxyAndNoExtraField() throws Exception {
		HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(origin() + "/config.js")));
		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("\"proxy\":{\"passthrough\":true}"), response.body());
		assertTrue(response.body().contains("{\"name\":\"Local\",\"url\":\"/fhir\"}"), response.body());
		assertFalse(response.body().contains("s3"), response.body());
	}

	@Test
	void formSearchOnFhirStillReadsBodyParameters() throws Exception {
		HttpResponse<String> created = send(HttpRequest.newBuilder(URI.create(origin() + "/fhir/Patient"))
				.POST(BodyPublishers.ofString("{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"ProxyItFamily\"}]}"))
				.header("Content-Type", "application/fhir+json"));
		assertEquals(201, created.statusCode());

		// If the server ignored the form body, the search would also return the Patient above.
		HttpResponse<String> search = send(HttpRequest.newBuilder(URI.create(origin() + "/fhir/Patient/_search"))
				.POST(BodyPublishers.ofString("family=NoSuchName"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.header("Accept", "application/fhir+json"));
		assertEquals(200, search.statusCode());
		assertTrue(search.body().matches("(?s).*\"total\"\\s*:\\s*0\\b.*"), search.body());
		assertFalse(search.body().contains("ProxyItFamily"), search.body());
	}

	private URI proxy(String destination) {
		return URI.create(origin() + proxyTarget(destination));
	}

	private static String proxyTarget(String destination) {
		// The same encoding as encodeURIComponent in the frontend, which writes a space as %20.
		return "/proxy?url=" + URLEncoder.encode(destination, UTF_8).replace("+", "%20");
	}

	private String rawRequest(String request) throws IOException {
		try (Socket socket = new Socket("localhost", port)) {
			socket.getOutputStream().write(request.getBytes(US_ASCII));
			return new String(socket.getInputStream().readAllBytes(), US_ASCII);
		}
	}

	private String origin() {
		return "http://localhost:" + port;
	}

	private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
		return HTTP.send(request.build(), BodyHandlers.ofString());
	}

	private static byte[] gzip(String text) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
			out.write(text.getBytes(UTF_8));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return bytes.toByteArray();
	}

	/**
	 * A filter that exists only in this test. For a request with the marker header, the body stream ends after half
	 * of the declared bytes and reports that it is not finished before the read.
	 */
	// No @TestConfiguration: the component scan of the server would add this class to the context of each test.
	static class ShortBodyFilterConfiguration {

		@Bean
		FilterRegistrationBean<Filter> shortBodyFilter() {
			FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
				HttpServletRequest http = (HttpServletRequest) request;
				chain.doFilter(http.getHeader(SHORT_BODY) == null ? request : new ShortBodyRequest(http), response);
			});
			registration.addUrlPatterns("/proxy");
			return registration;
		}
	}

	private static final class ShortBodyRequest extends HttpServletRequestWrapper {

		private final ServletInputStream body;

		ShortBodyRequest(HttpServletRequest request) throws IOException {
			super(request);
			byte[] bytes = request.getInputStream().readAllBytes();
			ByteArrayInputStream half = new ByteArrayInputStream(bytes, 0, bytes.length / 2);
			this.body = new ServletInputStream() {
				@Override
				public boolean isFinished() {
					return half.available() == 0;
				}

				@Override
				public boolean isReady() {
					return true;
				}

				@Override
				public void setReadListener(ReadListener listener) {
					throw new UnsupportedOperationException();
				}

				@Override
				public int read() {
					return half.read();
				}
			};
		}

		@Override
		public ServletInputStream getInputStream() {
			return body;
		}
	}

	// The port is the source port of the proxy connection, so two requests with one port share a connection.
	private record Received(String method, String path, String query, Headers headers, byte[] body, int port) {

		Map<String, String> decodedQuery() {
			Map<String, String> values = new HashMap<>();
			for (String pair : query.split("&")) {
				int equals = pair.indexOf('=');
				values.put(URLDecoder.decode(pair.substring(0, equals), UTF_8), URLDecoder.decode(pair.substring(equals + 1), UTF_8));
			}
			return values;
		}
	}

	/**
	 * A local HTTP server that records each request. The path selects the answer. The thread pool lets a
	 * slow answer run while the next test sends its requests.
	 */
	private static final class Stub {

		final List<Received> received = new CopyOnWriteArrayList<>();
		volatile long streamFinished;

		private final ExecutorService executor = Executors.newCachedThreadPool();
		private final HttpServer server;

		Stub() {
			try {
				server = HttpServer.create(new InetSocketAddress(0), 0);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			server.setExecutor(executor);
			server.createContext("/", this::handle);
			server.start();
		}

		int port() {
			return server.getAddress().getPort();
		}

		String base() {
			return "http://localhost:" + port();
		}

		Received only() {
			assertEquals(1, received.size());
			return received.get(0);
		}

		Received last() {
			return received.get(received.size() - 1);
		}

		void stop() {
			server.stop(0);
			executor.shutdownNow();
		}

		private void handle(HttpExchange exchange) throws IOException {
			boolean complete = true;
			try {
				URI uri = exchange.getRequestURI();
				// The early path answers before it reads the body, as an upstream that refuses a request at once.
				boolean early = "/fhir/early".equals(uri.getRawPath());
				byte[] requestBody = early ? new byte[0] : exchange.getRequestBody().readAllBytes();
				received.add(new Received(exchange.getRequestMethod(), uri.getRawPath(), uri.getRawQuery(),
						exchange.getRequestHeaders(), requestBody, exchange.getRemoteAddress().getPort()));
				Headers headers = exchange.getResponseHeaders();
				switch (uri.getRawPath()) {
					case "/fhir/truncated-length", "/fhir/truncated-chunked" -> {
						// A response length of 0 makes the JDK server send the body chunked.
						boolean fixed = "/fhir/truncated-length".equals(uri.getRawPath());
						exchange.sendResponseHeaders(200, fixed ? TRUNCATED_LENGTH : 0);
						exchange.getResponseBody().write(TRUNCATED_BODY);
						exchange.getResponseBody().flush();
						// An exception from the handler makes the JDK server drop the connection with no final chunk and
						// no remaining bytes.
						complete = false;
						throw new IOException("The stub drops the connection in the middle of the body");
					}
					case "/fhir/early" -> {
						respond(exchange, 200, "answered early".getBytes(UTF_8));
						// The flush sends the answer before close, which reads the rest of the declared body.
						exchange.getResponseBody().flush();
					}
					case "/fhir/cookie" -> {
						headers.add("Set-Cookie", "upstream=1; Path=/");
						headers.add("Access-Control-Allow-Origin", "*");
						headers.add("WWW-Authenticate", "Basic realm=\"stub\"");
						headers.add("Content-Security-Policy", "script-src *");
						headers.add("ETag", "\"v1\"");
						respond(exchange, 200, "with cookie".getBytes(UTF_8));
					}
					case "/fhir/unavailable" -> {
						headers.add("Retry-After", "1");
						respond(exchange, 503, "busy".getBytes(UTF_8));
					}
					case "/fhir/redirect" -> {
						headers.add("Location", "/next");
						exchange.sendResponseHeaders(302, -1);
					}
					case "/fhir/slow" -> {
						pause(5000);
						respond(exchange, 200, "late".getBytes(UTF_8));
					}
					case "/fhir/empty" -> exchange.sendResponseHeaders(204, -1);
					case "/fhir/head" -> {
						// For HEAD, the JDK server sends no body and keeps a Content-Length that the handler sets.
						headers.add("Content-Length", "42");
						exchange.sendResponseHeaders(200, -1);
					}
					case "/fhir/gzip" -> {
						headers.add("Content-Type", "application/fhir+json");
						headers.add("Content-Encoding", "gzip");
						respond(exchange, 200, GZIP_BODY);
					}
					case "/fhir/stream" -> {
						exchange.sendResponseHeaders(200, 0);
						OutputStream body = exchange.getResponseBody();
						body.write(FIRST_CHUNK);
						body.flush();
						pause(1000);
						streamFinished = System.nanoTime();
						body.write(SECOND_CHUNK);
					}
					default -> respond(exchange, 200, (exchange.getRequestMethod() + " " + uri.getRawPath()).getBytes(UTF_8));
				}
			} finally {
				if (complete) {
					exchange.close();
				}
			}
		}

		private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
			exchange.sendResponseHeaders(status, body.length);
			exchange.getResponseBody().write(body);
		}

		private static void pause(long millis) {
			try {
				Thread.sleep(millis);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}
}
