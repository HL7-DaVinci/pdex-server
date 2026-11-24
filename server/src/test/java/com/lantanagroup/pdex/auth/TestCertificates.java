package com.lantanagroup.pdex.auth;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/** Makes test certificates with the keytool of the JDK that runs the test. */
public final class TestCertificates {

	private static final String KEYSTORE_PASSWORD = "changeit";

	private TestCertificates() {}

	/**
	 * Returns an HTTPS server at https://localhost on a free port. The caller starts it. Its certificate names only
	 * test.invalid and untrusted.test, so a client that checks the chain or the host name rejects the server.
	 */
	public static HttpsServer untrustedHttpsServer(Path directory) throws Exception {
		Path keystore = selfSigned(directory, "RSA", "uri:https://untrusted.test", KEYSTORE_PASSWORD);
		KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		keyManagers.init(
				KeyStore.getInstance(keystore.toFile(), KEYSTORE_PASSWORD.toCharArray()), KEYSTORE_PASSWORD.toCharArray());
		SSLContext context = SSLContext.getInstance("TLS");
		context.init(keyManagers.getKeyManagers(), null, null);
		HttpsServer server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
		server.setHttpsConfigurator(new HttpsConfigurator(context));
		return server;
	}

	/**
	 * Writes a PKCS#12 keystore with one self-signed key entry for CN=test.invalid to the directory and returns its
	 * path. The subject alternative name is a value for keytool -ext san=, for example uri:https://issuer.test.
	 */
	public static Path selfSigned(Path directory, String keyAlgorithm, String subjectAlternativeName, String password)
			throws IOException, InterruptedException {
		Path keystore = directory.resolve("test-certificate.keystore");
		Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
		Process process = new ProcessBuilder(
						keytool.toString(), "-genkeypair", "-alias", "test", "-keyalg", keyAlgorithm,
						"-dname", "CN=test.invalid", "-ext", "san=" + subjectAlternativeName, "-validity", "1",
						"-storetype", "PKCS12", "-keystore", keystore.toString(),
						"-storepass", password, "-keypass", password)
				.redirectErrorStream(true)
				.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (process.waitFor() != 0) {
			throw new IllegalStateException("keytool failed: " + output);
		}
		return keystore;
	}
}
