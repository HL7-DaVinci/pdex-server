package com.lantanagroup.pdex.proxy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.http.client.HttpComponentsClientHttpRequestFactoryBuilder;
import org.springframework.http.client.ClientHttpRequestFactory;

/**
 * The two Apache HttpClient 5 request factories of the proxy. Neither client keeps cookies, follows a
 * redirect, decompresses a response, retries a request, answers an authentication challenge, or adds a
 * User-Agent. The body bytes of each upstream answer pass as the upstream sent them, and the client adds no
 * credentials.
 */
public final class ProxyHttpClients {

	private ProxyHttpClients() {}

	// The connection manager connects to exactly the addresses that resolve returns, so there is no second
	// lookup. Only the Kerberos and SPNEGO schemes ask for a canonical name, and authentication is off. The
	// name comes back with no lookup.
	private static final DnsResolver PUBLIC_ONLY = new DnsResolver() {
		@Override
		public InetAddress[] resolve(String host) throws UnknownHostException {
			return PublicAddresses.resolve(host);
		}

		@Override
		public String resolveCanonicalHostname(String host) {
			return host;
		}
	};

	/** The client for listed servers. A listed server can be an internal host, so this client has no guard. */
	public static ClientHttpRequestFactory listed(ClientHttpRequestFactorySettings settings) {
		return base().build(settings);
	}

	/** The client for passthrough. It connects only to public addresses. */
	public static ClientHttpRequestFactory publicOnly(ClientHttpRequestFactorySettings settings) {
		return base()
				.withConnectionManagerCustomizer(manager -> manager.setDnsResolver(PUBLIC_ONLY))
				// Spring Boot builds the client from system properties, which can route a request through a JVM
				// proxy. The resolver then sees only the proxy host, so this client never uses a proxy.
				.withHttpClientCustomizer(client -> client.setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE)))
				.build(settings);
	}

	// A retry of a 429 or 503 response waits for the Retry-After value of the upstream. Authentication with
	// system properties can send credentials of a JVM Authenticator to the upstream.
	/** The builder of both clients. */
	public static HttpComponentsClientHttpRequestFactoryBuilder base() {
		return ClientHttpRequestFactoryBuilder.httpComponents()
				.withHttpClientCustomizer(client -> client
						.disableCookieManagement()
						.disableRedirectHandling()
						.disableContentCompression()
						.disableAutomaticRetries()
						.disableDefaultUserAgent())
				.withDefaultRequestConfigCustomizer(config -> config.setAuthenticationEnabled(false));
	}
}
