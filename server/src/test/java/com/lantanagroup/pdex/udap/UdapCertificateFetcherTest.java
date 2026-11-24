package com.lantanagroup.pdex.udap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lantanagroup.pdex.auth.TestCertificates;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the fetch against the JDK HTTP and HTTPS servers. */
class UdapCertificateFetcherTest {

	static final String BASE_URL = "http://localhost:8080/fhir";
	static final String PASSWORD = "pass\"word";
	static final byte[] CERTIFICATE = {1, 2, 3, 4};
	static final long DELAY_MILLIS = 200;

	@TempDir
	Path directory;

	final List<HttpServer> servers = new ArrayList<>();
	final List<Long> requestNanos = new CopyOnWriteArrayList<>();
	final Queue<Integer> statuses = new ConcurrentLinkedQueue<>();

	Path target;
	String url;

	@BeforeEach
	void start() throws IOException {
		target = directory.resolve("fetched.keystore");
		HttpServer server = serve(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
		url = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterEach
	void stop() {
		servers.forEach(server -> server.stop(0));
	}

	/** Each request gets the next status of the queue, or 200 with the certificate when the queue is empty. */
	HttpServer serve(HttpServer server) {
		server.createContext("/", this::answer);
		server.start();
		servers.add(server);
		return server;
	}

	void answer(HttpExchange exchange) throws IOException {
		requestNanos.add(System.nanoTime());
		exchange.getRequestBody().readAllBytes();
		Integer status = statuses.poll();
		byte[] body = status == null ? CERTIFICATE : "failed".getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status == null ? 200 : status, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	String startUntrustedTlsServer() throws Exception {
		HttpsServer server = TestCertificates.untrustedHttpsServer(directory);
		serve(server);
		return "https://localhost:" + server.getAddress().getPort();
	}

	static UdapCertificateFetcher fetcher(boolean sslVerify, String issuer, int attempts) {
		return new UdapCertificateFetcher(UdapConfig.fetchClient(sslVerify), issuer, attempts, DELAY_MILLIS);
	}

	@Test
	void fetchTriesAgainUntilTheLastTry() throws Exception {
		statuses.add(500);

		fetcher(true, url, 2).fetch(BASE_URL, PASSWORD, target);

		assertEquals(2, requestNanos.size());
		assertArrayEquals(CERTIFICATE, Files.readAllBytes(target));
		long gapNanos = requestNanos.get(1) - requestNanos.get(0);
		assertTrue(gapNanos >= TimeUnit.MILLISECONDS.toNanos(DELAY_MILLIS), gapNanos + " ns between the tries");

		requestNanos.clear();
		Path failedTarget = directory.resolve("failed.keystore");
		statuses.addAll(List.of(500, 503));
		UdapCertificateFetcher fetcher = fetcher(true, url, 2);

		assertThrows(IllegalStateException.class, () -> fetcher.fetch(BASE_URL, PASSWORD, failedTarget));

		assertEquals(2, requestNanos.size());
		assertFalse(Files.exists(failedTarget));
	}

	@Test
	void writeErrorStopsWithNoNewTry() {
		Path unwritable = directory.resolve("missing-folder").resolve("fetched.keystore");
		UdapCertificateFetcher fetcher = fetcher(true, url, 3);

		assertThrows(IllegalStateException.class, () -> fetcher.fetch(BASE_URL, PASSWORD, unwritable));

		assertEquals(1, requestNanos.size());
	}

	@Test
	void sslVerifySwitchesTheCertificateCheck() throws Exception {
		String tlsUrl = startUntrustedTlsServer();

		fetcher(false, tlsUrl, 1).fetch(BASE_URL, PASSWORD, target);

		assertArrayEquals(CERTIFICATE, Files.readAllBytes(target));

		Path verifiedTarget = directory.resolve("verified.keystore");
		UdapCertificateFetcher fetcher = fetcher(true, tlsUrl, 1);

		assertThrows(IllegalStateException.class, () -> fetcher.fetch(BASE_URL, PASSWORD, verifiedTarget));

		assertFalse(Files.exists(verifiedTarget));
	}
}
