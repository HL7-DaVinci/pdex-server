package com.lantanagroup.pdex.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.function.Supplier;

/**
 * An issuer for the integration tests: a JDK HTTP server on 127.0.0.1 with one RSA key. It serves the key set at
 * {url}/jwks and serves no other document until a test adds one.
 */
public final class TestIssuer {

	private final RSAKey key;
	private final HttpServer server;

	/** Starts the server. */
	public TestIssuer() {
		try {
			key = new RSAKeyGenerator(2048).keyID("it-rsa").generate();
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		} catch (JOSEException | IOException e) {
			throw new IllegalStateException("Could not start the test issuer", e);
		}
		serve("/jwks", () -> new JWKSet(key).toJSONObject());
		server.start();
	}

	public String url() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/issuer";
	}

	/** Answers each request to {url}{path} with status 200 and the JSON object that the supplier gives. */
	public TestIssuer serve(String path, Supplier<Map<String, Object>> document) {
		server.createContext("/issuer" + path, exchange -> {
			byte[] body = JSONObjectUtils.toJSONString(document.get()).getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		return this;
	}

	/**
	 * Signs the claims with RS256 and the key of the issuer, with the key id in the header. It sets the issuer and an
	 * expiration time 5 minutes ahead.
	 */
	public String sign(JWTClaimsSet.Builder claims) throws JOSEException {
		SignedJWT jwt = new SignedJWT(
				new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
				claims.issuer(url()).expirationTime(new Date(System.currentTimeMillis() + 300_000)).build());
		jwt.sign(new RSASSASigner(key));
		return jwt.serialize();
	}

	public void stop() {
		server.stop(0);
	}
}
