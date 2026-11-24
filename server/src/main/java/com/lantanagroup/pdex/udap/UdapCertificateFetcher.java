package com.lantanagroup.pdex.udap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Gets a certificate for the base URL from the /api/cert/generate endpoint of the issuer, a FAST security server. */
public class UdapCertificateFetcher {

	private static final Logger log = LoggerFactory.getLogger(UdapCertificateFetcher.class);

	private static final ObjectMapper JSON = new ObjectMapper();

	/**
	 * The limit of one try, from the send to the complete response body. The request timeout of the JDK client ends
	 * at the response headers, so it does not stop a body that never ends.
	 */
	private static final long TRY_TIMEOUT_SECONDS = 30;

	private final HttpClient client;

	private final URI generateUri;

	private final int attempts;

	private final long delayMillis;

	public UdapCertificateFetcher(HttpClient client, String issuer, int attempts, long delayMillis) {
		this.client = client;
		this.generateUri = URI.create(Strings.CS.removeEnd(issuer, "/") + "/api/cert/generate");
		this.attempts = attempts;
		this.delayMillis = delayMillis;
	}

	/**
	 * Sends the base URL as the alternative name and writes the body of a response with status 200 to the target file.
	 *
	 * @throws IllegalStateException after the last failed try, when the write fails, or when the thread is
	 *     interrupted.
	 */
	public void fetch(String baseUrl, String password, Path target) {
		log.info("Fetching a UDAP certificate from {}", generateUri);
		try {
			Files.write(target, download(baseUrl, password));
		} catch (IOException e) {
			throw removeTarget(target, new IllegalStateException("Could not write the certificate to " + target, e));
		}
		log.info("Saved the UDAP certificate to {}", target);
	}

	/** Returns the body of the first response with status 200. */
	private byte[] download(String baseUrl, String password) {
		ObjectNode body = JSON.createObjectNode();
		body.putArray("altNames").add(baseUrl);
		body.put("password", password);
		HttpRequest request = HttpRequest.newBuilder(generateUri)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();
		String failure = "no try";
		for (int attempt = 1; attempt <= attempts; attempt++) {
			CompletableFuture<HttpResponse<byte[]>> pending = null;
			try {
				if (attempt > 1 && delayMillis > 0) {
					Thread.sleep(delayMillis);
				}
				pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
				HttpResponse<byte[]> response = pending.get(TRY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
				if (response.statusCode() == 200) {
					return response.body();
				}
				failure = "status " + response.statusCode();
			} catch (TimeoutException e) {
				pending.cancel(true);
				failure = "no complete response in " + TRY_TIMEOUT_SECONDS + " seconds";
			} catch (ExecutionException e) {
				failure = e.getCause().toString();
			} catch (InterruptedException e) {
				// Cancels the exchange, so that it does not go on after the fetch stops.
				if (pending != null) {
					pending.cancel(true);
				}
				Thread.currentThread().interrupt();
				throw new IllegalStateException("The certificate fetch from " + generateUri + " was interrupted", e);
			}
			log.warn("Certificate fetch try {} of {} failed with {}", attempt, attempts, failure);
		}
		throw new IllegalStateException("Could not fetch a certificate from " + generateUri + " in " + attempts
				+ " tries. The last try failed with " + failure + ".");
	}

	/** Deletes the target file, so that the next start does not load a partial file. */
	private static RuntimeException removeTarget(Path target, RuntimeException failure) {
		try {
			Files.deleteIfExists(target);
		} catch (IOException e) {
			failure.addSuppressed(e);
		}
		return failure;
	}
}
