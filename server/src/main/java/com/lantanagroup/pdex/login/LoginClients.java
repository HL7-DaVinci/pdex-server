package com.lantanagroup.pdex.login;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.lantanagroup.pdex.config.FhirServers;
import com.lantanagroup.pdex.proxy.ProxyDestinations;
import com.lantanagroup.pdex.proxy.ProxyHttpClients;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.http.HTTPRequest;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.http.ReadOnlyHTTPRequest;
import com.nimbusds.oauth2.sdk.util.JSONObjectUtils;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import net.minidev.json.JSONObject;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Finds which kind of FHIR server a sign-in is for, and holds the two HTTP clients that reach that server and
 * its authorization server. The SDK builds and parses each message, and each message goes out through a
 * RestClient of this class.
 */
@Component
public class LoginClients {

	private static final Logger log = LoggerFactory.getLogger(LoginClients.class);

	private static final String OWN_SERVER = "/fhir";

	// With passthrough on, any caller can point the status call at a server that sends an endless body, so the
	// client reads at most this many bytes of a response into memory.
	private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

	// java.net.URI gives an IPv4 literal host as four decimal parts and an IPv6 literal host in brackets.
	private static final Pattern IPV4_LITERAL = Pattern.compile("[0-9]{1,3}(\\.[0-9]{1,3}){3}");

	public enum Kind { OWN, LISTED, TYPED }

	/**
	 * fhirBase is the FHIR base as the tester gave it, with its trailing slash if it has one. It is the aud value
	 * and the base for a relative authorization endpoint. discoveryBase is the base where the server reads, and the
	 * base for a relative endpoint that the server calls.
	 */
	public record Target(Kind kind, URI fhirBase, URI discoveryBase) {

		/** Token sets are keyed by this form, so https://host/fhir and https://host/fhir/ are one sign-in. */
		public URI fhirBaseWithoutSlash() {
			return withoutTrailingSlash(fhirBase);
		}
	}

	/** Each URI is resolved and checked. The published strings are the values as the discovery document has them. */
	public record Discovery(URI authorize, String authorizePublished, URI token, String tokenPublished,
			List<String> capabilities, String issuer, URI jwksUri) {}

	/** The issuer of the SMART configuration and its resolved jwks_uri, which can be null. */
	public record IssuerKeys(String issuer, URI jwksUri) {}

	public static final class RefusedServerException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		RefusedServerException() {
			super("The server is not under a listed server, and passthrough is off");
		}
	}

	/** Thrown for a plain http URL on a host that is not localhost or a loopback address. */
	public static final class PlainHttpException extends IllegalArgumentException {
		private static final long serialVersionUID = 1L;

		PlainHttpException() {
			super("The sign-in accepts plain http only for localhost and loopback addresses");
		}
	}

	private final List<URI> listedBases;
	private final boolean passthrough;
	private final RestClient unguarded;
	private final RestClient publicOnly;

	public LoginClients(
			FhirServers fhirServers,
			ClientHttpRequestFactorySettings settings,
			@Value("${app.login.enabled:false}") boolean enabled,
			@Value("${app.ssl-verify:true}") boolean sslVerify,
			@Value("${app.proxy.passthrough:false}") boolean passthrough,
			@Value("${app.proxy.connect-timeout:5s}") Duration connectTimeout,
			@Value("${app.proxy.read-timeout:60s}") Duration readTimeout) {
		// A root-relative entry is on the origin of the page and has no scheme or host to classify against.
		this.listedBases = fhirServers.entries().stream()
				.filter(entry -> !entry.url().startsWith("/"))
				.map(entry -> URI.create(entry.url()))
				.toList();
		this.passthrough = passthrough;
		ClientHttpRequestFactorySettings timeouts = settings.withConnectTimeout(connectTimeout).withReadTimeout(readTimeout);
		ClientHttpRequestFactory unguardedFactory = sslVerify ? ProxyHttpClients.listed(timeouts) : trustAll(timeouts);
		this.unguarded = RestClient.builder().requestFactory(unguardedFactory).build();
		this.publicOnly = RestClient.builder().requestFactory(ProxyHttpClients.publicOnly(timeouts)).build();
		if (enabled && !sslVerify) {
			log.warn("app.ssl-verify is false, so the sign-in accepts any certificate and any host name"
					+ " from the own server, the listed servers, and their authorization servers");
		}
	}

	/**
	 * @param server /fhir for the own server, else the absolute URL of the FHIR server
	 * @param origin the origin of the app in the browser, used for the own server only
	 * @param localPort the port of the connection that carried the request, used for the loopback read
	 * @throws IllegalArgumentException for a server value that is not a valid URL or has a query or a fragment
	 * @throws PlainHttpException for a server value that requireHttp refuses
	 * @throws RefusedServerException for a server under no listed server while passthrough is off
	 */
	public Target target(String server, String origin, int localPort) {
		if (OWN_SERVER.equals(server)) {
			return new Target(Kind.OWN, URI.create(origin + OWN_SERVER),
					URI.create("http://127.0.0.1:" + localPort + OWN_SERVER));
		}
		URI parsed = ProxyDestinations.parse(server);
		// The discovery URL is the base followed by a path, so a query or a fragment in the base breaks it.
		if (parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
			throw new IllegalArgumentException("The server value must not have a query or a fragment");
		}
		// The access token of a listed or typed server goes over the network to this URL. The token of the own
		// server stays inside this server.
		requireHttp(parsed);
		// SMART App Launch resolves a relative endpoint against the FHIR base URL, and aud is that URL. So the base
		// keeps the trailing slash that the tester gave.
		ProxyDestinations.Decision decision = ProxyDestinations.classify(parsed, listedBases, passthrough);
		return switch (decision.route()) {
			case LISTED -> new Target(Kind.LISTED, parsed, decision.upstream());
			case PASSTHROUGH -> new Target(Kind.TYPED, parsed, parsed);
			case REFUSED -> throw new RefusedServerException();
		};
	}

	/**
	 * Reads the SMART configuration of the target. Each endpoint must pass requireHttp after resolution, so a
	 * bad value stops the sign-in before the browser goes to the authorization server.
	 *
	 * @throws IllegalArgumentException for an endpoint that is missing or that requireHttp refuses
	 */
	public Discovery discover(Target target) throws IOException, ParseException {
		JSONObject json = smartConfiguration(target);
		String authorizePublished = JSONObjectUtils.getString(json, "authorization_endpoint", null);
		String tokenPublished = JSONObjectUtils.getString(json, "token_endpoint", null);
		String issuer = JSONObjectUtils.getString(json, "issuer", null);
		if (issuer != null) {
			requireHttp(wellKnown(URI.create(issuer), "openid-configuration"));
		}
		String jwksUri = JSONObjectUtils.getString(json, "jwks_uri", null);
		// The server itself calls the token endpoint and the key set, so a relative value resolves against the
		// address where the server reads. For the own server that is the loopback address, so an Origin header that
		// a caller makes up has no effect on where the token call goes. The browser opens the authorization
		// endpoint, so a relative value resolves against the FHIR base that the browser uses.
		return new Discovery(
				resolve(target.fhirBase(), authorizePublished),
				authorizePublished,
				resolve(target.discoveryBase(), tokenPublished),
				tokenPublished,
				JSONObjectUtils.getStringList(json, "capabilities", List.of()),
				issuer,
				jwksUri == null ? null : resolve(target.discoveryBase(), jwksUri));
	}

	/**
	 * Reads only the issuer and the jwks_uri of the SMART configuration of the target, so a document with no
	 * endpoints still gives the issuer. The jwks_uri resolves as in discover.
	 *
	 * @return empty when the read fails or the document has no issuer
	 * @throws IllegalArgumentException for an issuer or a jwks_uri that requireHttp refuses
	 */
	public Optional<IssuerKeys> issuer(Target target) {
		String issuer;
		String jwksUri;
		try {
			JSONObject json = smartConfiguration(target);
			issuer = JSONObjectUtils.getString(json, "issuer", null);
			jwksUri = JSONObjectUtils.getString(json, "jwks_uri", null);
		} catch (IOException | ParseException e) {
			return Optional.empty();
		}
		if (issuer == null) {
			return Optional.empty();
		}
		requireHttp(wellKnown(URI.create(issuer), "openid-configuration"));
		return Optional.of(new IssuerKeys(issuer, jwksUri == null ? null : resolve(target.discoveryBase(), jwksUri)));
	}

	private JSONObject smartConfiguration(Target target) throws IOException, ParseException {
		HTTPRequest request = new HTTPRequest(HTTPRequest.Method.GET, wellKnown(target.discoveryBase(), "smart-configuration"));
		request.setAccept("application/json");
		HTTPResponse response = send(target.kind(), request);
		response.ensureStatusCode(200);
		return JSONObjectUtils.parse(response.getBody());
	}

	/**
	 * OpenID Connect Discovery 1.0 section 4.1 removes a terminating slash of the issuer before the suffix. The
	 * SMART configuration URL uses the same rule, so a base with a trailing slash gives one slash.
	 */
	static URI wellKnown(URI base, String name) {
		return URI.create(withoutTrailingSlash(base) + "/.well-known/" + name);
	}

	/** Each outbound call of the sign-in goes through this method. A typed server gets the public-only client. */
	public HTTPResponse send(Kind kind, HTTPRequest request) throws IOException {
		RestClient client = kind == Kind.TYPED ? publicOnly : unguarded;
		try {
			return request.send(message -> exchange(client, message));
		} catch (ResourceAccessException e) {
			// The message of this exception holds the full request URL, so the message of the replacement is fixed.
			throw new IOException("The request failed", e);
		}
	}

	/**
	 * Resolves an endpoint against a base URL as written (RFC 3986). Against a base with no trailing slash, a
	 * relative endpoint replaces the last path segment.
	 *
	 * @throws IllegalArgumentException for a null endpoint or a result that requireHttp refuses
	 */
	static URI resolve(URI base, String endpoint) {
		if (endpoint == null) {
			throw new IllegalArgumentException("The discovery document has no value for an endpoint");
		}
		return requireHttp(base.resolve(endpoint));
	}

	/**
	 * Accepts an https URL, and an http URL whose host is localhost, a name that ends in .localhost, or a
	 * loopback IP address literal. The check does no DNS lookup.
	 *
	 * @throws PlainHttpException for an http URL on a different host
	 * @throws IllegalArgumentException for a URL that is not an absolute http or https URL
	 */
	static URI requireHttp(URI uri) {
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
			throw new IllegalArgumentException("An endpoint is not an absolute http or https URL");
		}
		String host = uri.getHost().toLowerCase(Locale.ROOT);
		if (scheme.equals("https") || host.equals("localhost") || host.endsWith(".localhost")) {
			return uri;
		}
		// InetAddress parses an IP address literal as text. For a name it does a DNS lookup, so a name never
		// goes to InetAddress.
		try {
			if ((host.startsWith("[") || IPV4_LITERAL.matcher(host).matches())
					&& InetAddress.getByName(host).isLoopbackAddress()) {
				return uri;
			}
		} catch (UnknownHostException e) {
			throw new PlainHttpException();
		}
		throw new PlainHttpException();
	}

	// The SDK parses a token error from a 400 or 401 response. RestClient.retrieve throws on such a status
	// before the SDK gets the response, so the call uses exchange.
	private static HTTPResponse exchange(RestClient client, ReadOnlyHTTPRequest message) {
		RestClient.RequestBodySpec spec = client.method(HttpMethod.valueOf(message.getMethod().name()))
				.uri(message.getURI())
				.headers(headers -> message.getHeaderMap().forEach(headers::addAll));
		if (message.getBody() != null) {
			byte[] body = message.getBody().getBytes(UTF_8);
			// RFC 9112 section 6.1: a client sends Transfer-Encoding only to a server that it knows handles HTTP/1.1,
			// so the request gives the length of its body.
			spec.contentLength(body.length).body(out -> out.write(body));
		}
		return spec.exchange((request, response) -> {
			HTTPResponse out = new HTTPResponse(response.getStatusCode().value());
			response.getHeaders().forEach((name, values) -> out.setHeader(name, values.toArray(String[]::new)));
			byte[] body = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
			if (body.length > MAX_RESPONSE_BYTES) {
				throw new IOException("The response body is larger than 1 MiB");
			}
			out.setBody(new String(body, UTF_8));
			return out;
		});
	}

	private static URI withoutTrailingSlash(URI uri) {
		String text = uri.toString();
		return text.endsWith("/") ? URI.create(text.substring(0, text.length() - 1)) : uri;
	}

	// When a TLS strategy has a host name verifier, HttpClient runs it after the handshake, apart from the trust
	// manager. The verifier of this client accepts each name. The context goes to this client only and never
	// becomes the JVM default.
	private static ClientHttpRequestFactory trustAll(ClientHttpRequestFactorySettings settings) {
		TlsSocketStrategy tls = new DefaultClientTlsStrategy(trustAllContext(), NoopHostnameVerifier.INSTANCE);
		return ProxyHttpClients.base()
				.withConnectionManagerCustomizer(manager -> manager.setTlsSocketStrategy(tls))
				.build(settings);
	}

	// The JDK gives the host name check to an extended trust manager, so its empty checks turn off that check too.
	private static SSLContext trustAllContext() {
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
