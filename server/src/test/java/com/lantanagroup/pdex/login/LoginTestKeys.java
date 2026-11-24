package com.lantanagroup.pdex.login;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

/** Makes test keys with the keytool of the JDK that runs the test. */
final class LoginTestKeys {

	private static final char[] PASSWORD = "changeit".toCharArray();

	private LoginTestKeys() {}

	/**
	 * Returns an RSA 2048 key with a self-signed certificate for CN=test.invalid that is valid for one day. The
	 * subject alternative name is a value for keytool -ext san=, for example uri:https://app.test/fhir. The method
	 * deletes the keystore file and its folder before it returns.
	 */
	static KeyStore.PrivateKeyEntry create(String subjectAlternativeName) {
		try {
			Path directory = Files.createTempDirectory("login-test-keys");
			Path keystore = directory.resolve("key-store");
			try {
				Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
				Process process = new ProcessBuilder(
								keytool.toString(), "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
								"-dname", "CN=test.invalid", "-ext", "san=" + subjectAlternativeName, "-validity", "1",
								"-storetype", "PKCS12", "-keystore", keystore.toString(),
								"-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD))
						.redirectErrorStream(true)
						.start();
				String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				if (process.waitFor() != 0) {
					throw new IllegalStateException("keytool failed: " + output);
				}
				KeyStore store = KeyStore.getInstance(keystore.toFile(), PASSWORD);
				return (KeyStore.PrivateKeyEntry) store.getEntry("test", new KeyStore.PasswordProtection(PASSWORD));
			} finally {
				Files.deleteIfExists(keystore);
				Files.delete(directory);
			}
		} catch (IOException | GeneralSecurityException | InterruptedException e) {
			throw new IllegalStateException("Could not make a test key with keytool", e);
		}
	}
}
