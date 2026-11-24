package com.lantanagroup.pdex.auth;

import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * An SSL context that accepts each server certificate and each host name. Only the HTTP clients of the auth features
 * get it, when app.ssl-verify is false. It never becomes the JVM default.
 */
public final class TrustAllSslContext {

	private TrustAllSslContext() {}

	/** The JDK gives the host name check to an extended trust manager, so its empty checks turn off both checks. */
	public static SSLContext create() {
		X509ExtendedTrustManager trustAll = new X509ExtendedTrustManager() {
			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType) {}

			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType) {}

			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {}

			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {}

			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}

			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}

			@Override
			public X509Certificate[] getAcceptedIssuers() {
				return new X509Certificate[0];
			}
		};
		try {
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(null, new TrustManager[] {trustAll}, new SecureRandom());
			return context;
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Could not create the SSL context for app.ssl-verify false", e);
		}
	}
}
