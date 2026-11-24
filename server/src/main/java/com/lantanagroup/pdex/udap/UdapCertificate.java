package com.lantanagroup.pdex.udap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The RSA key that signs the UDAP metadata, and the certificate chain of that key, leaf first. */
public record UdapCertificate(RSAPrivateKey key, List<X509Certificate> chain) {

	/**
	 * Loads the first key entry of a PKCS#12 file and the certificate chain of that entry.
	 *
	 * @throws IllegalStateException when the file fails to load, has no key entry, has no certificate chain
	 *     for its key, or the key is not an RSA key
	 */
	public static UdapCertificate load(Path file, String password) {
		char[] secret = password.toCharArray();
		try (InputStream in = Files.newInputStream(file)) {
			KeyStore keyStore = KeyStore.getInstance("PKCS12");
			keyStore.load(in, secret);
			for (String alias : Collections.list(keyStore.aliases())) {
				if (keyStore.isKeyEntry(alias)) {
					if (!(keyStore.getKey(alias, secret) instanceof RSAPrivateKey key)) {
						throw new IllegalStateException(
								"The key of " + file + " is not an RSA key. The signed metadata uses RS256.");
					}
					Certificate[] certificates = keyStore.getCertificateChain(alias);
					if (certificates == null) {
						throw new IllegalStateException(file + " has no certificate chain for its key");
					}
					List<X509Certificate> chain = Arrays.stream(certificates)
							.map(X509Certificate.class::cast)
							.toList();
					return new UdapCertificate(key, chain);
				}
			}
			throw new IllegalStateException(file + " has no key entry");
		} catch (IOException | GeneralSecurityException e) {
			throw new IllegalStateException("Could not load the certificate file " + file, e);
		}
	}
}
