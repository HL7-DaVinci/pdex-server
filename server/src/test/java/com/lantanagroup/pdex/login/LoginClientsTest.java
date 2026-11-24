package com.lantanagroup.pdex.login;

import com.lantanagroup.pdex.config.FhirServers;
import com.lantanagroup.pdex.login.LoginClients.Kind;
import com.lantanagroup.pdex.login.LoginClients.PlainHttpException;
import com.lantanagroup.pdex.login.LoginClients.RefusedServerException;
import com.lantanagroup.pdex.login.LoginClients.Target;
import com.lantanagroup.pdex.proxy.PublicAddresses.BlockedAddressException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoginClientsTest {

	private static final String LISTED = "https://listed.example/fhir";
	private static final String APP = "https://app.example";
	private static final URI HOST_FHIR = URI.create("https://host/fhir");

	@Test
	void ownServerIsTheFhirPathWithLoopbackDiscovery() {
		Target target = clients(false).target("/fhir", APP, 8123);
		assertEquals(Kind.OWN, target.kind());
		assertEquals(URI.create("https://app.example/fhir"), target.fhirBase());
		assertEquals(URI.create("http://127.0.0.1:8123/fhir"), target.discoveryBase());
	}

	@Test
	void listedTypedAndRefusedServers() {
		Target listed = clients(false).target(LISTED + "/", APP, 8123);
		assertEquals(Kind.LISTED, listed.kind());
		assertEquals(URI.create(LISTED + "/"), listed.fhirBase());
		assertEquals(URI.create(LISTED + "/.well-known/smart-configuration"),
				LoginClients.wellKnown(listed.discoveryBase(), "smart-configuration"));
		assertEquals(URI.create(LISTED + "/oauth/token"), LoginClients.resolve(listed.discoveryBase(), "oauth/token"));
		for (String server : List.of("https://listed.example/fhir2", "https://other.example/fhir")) {
			assertEquals(Kind.TYPED, clients(true).target(server, APP, 8123).kind(), server);
			assertThrows(RefusedServerException.class, () -> clients(false).target(server, APP, 8123), server);
		}
		for (String server : List.of(LISTED + "?x=1", LISTED + "#a")) {
			assertThrows(IllegalArgumentException.class, () -> clients(true).target(server, APP, 8123), server);
		}
	}

	@Test
	void relativeEndpointsResolveAgainstTheFhirBase() {
		assertEquals(URI.create("https://host/oauth/token"), LoginClients.resolve(HOST_FHIR, "oauth/token"));
		assertEquals(URI.create("https://host/oauth/token"), LoginClients.resolve(HOST_FHIR, "/oauth/token"));
		assertEquals(URI.create("https://host/fhir/oauth/token"),
				LoginClients.resolve(URI.create("https://host/fhir/"), "oauth/token"));
		assertEquals(URI.create("https://as.example/token"), LoginClients.resolve(HOST_FHIR, "https://as.example/token"));
	}

	@Test
	void endpointThatIsNotAbsoluteHttpIsRefused() {
		for (String endpoint : Arrays.asList("ftp://h/t", "javascript:alert(1)", "mailto:a@b", null)) {
			assertThrows(IllegalArgumentException.class, () -> LoginClients.resolve(HOST_FHIR, endpoint), endpoint);
		}
	}

	@Test
	void plainHttpPassesOnlyForLocalhostAndLoopbackAddresses() {
		for (String url : List.of("https://as.example/t", "http://localhost:8080/t", "http://app.localhost/t",
				"http://127.0.0.2/t", "http://[::1]:8080/t")) {
			assertEquals(URI.create(url), LoginClients.requireHttp(URI.create(url)), url);
		}
		for (String url : List.of("http://as.example:8080/t", "http://localhost.example/t", "http://10.0.0.5/t")) {
			assertThrows(PlainHttpException.class, () -> LoginClients.requireHttp(URI.create(url)), url);
		}
	}

	@Test
	void typedServerOnALoopbackAddressGetsNoRequest() throws Exception {
		AtomicInteger requests = new AtomicInteger();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			requests.incrementAndGet();
			byte[] body = "{\"authorization_endpoint\":\"oauth/authorize\",\"token_endpoint\":\"oauth/token\"}"
					.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			int port = server.getAddress().getPort();
			URI base = URI.create("http://127.0.0.1:" + port + "/fhir");
			IOException e = assertThrows(IOException.class, () -> clients(true).discover(new Target(Kind.TYPED, base, base)));
			assertInstanceOf(BlockedAddressException.class, NestedExceptionUtils.getMostSpecificCause(e));
			assertEquals(0, requests.get());
			// The server calls a relative token endpoint of the own server on the loopback address, whatever the Origin.
			Target own = clients(false).target("/fhir", "https://10.0.0.5:6379", port);
			assertEquals("127.0.0.1", clients(false).discover(own).token().getHost());
		} finally {
			server.stop(0);
		}
	}

	private static LoginClients clients(boolean passthrough) {
		MockEnvironment environment = new MockEnvironment()
				.withProperty("app.fhir.servers[0].name", "Listed")
				.withProperty("app.fhir.servers[0].url", LISTED);
		return new LoginClients(new FhirServers(environment), ClientHttpRequestFactorySettings.defaults(), true, true,
				passthrough, Duration.ofSeconds(5), Duration.ofSeconds(60));
	}
}
