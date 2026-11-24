package com.lantanagroup.pdex.proxy;

import com.lantanagroup.pdex.config.FhirServers;
import com.lantanagroup.pdex.proxy.PublicAddresses.BlockedAddressException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * The same-origin proxy endpoint /proxy?url=destination. A destination under a server of app.fhir.servers
 * goes out through the client with no guard. With passthrough on, any other destination goes out through the
 * client that connects only to public addresses. The proxy writes its own errors as short plain text and
 * never calls sendError, because the error dispatch of the container lets the frontend error view replace
 * the response.
 */
@RestController
public class FrontendProxyController {

	private static final Logger log = LoggerFactory.getLogger(FrontendProxyController.class);

	static final String GUARD_REFUSAL = "The destination address is not public";
	static final String INCOMPLETE_BODY = "The request body is incomplete or was already read by the server. The proxy"
			+ " cannot forward a form-encoded PUT, PATCH, or DELETE body or a multipart body.";

	private static final String METHODS = "GET, HEAD, POST, PUT, PATCH, DELETE";
	private static final Set<String> FORWARDED_METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE");

	// Hop-by-hop headers, the cookies and origin of this application, and headers that a browser can forge.
	// X-App-Request shows this application that its own app sent the request.
	// The X-Forwarded- and Sec- prefixes are checked separately.
	private static final Set<String> DENIED_REQUEST_HEADERS = Set.of("host", "cookie", "origin", "referer",
			"connection", "keep-alive", "te", "trailer", "transfer-encoding", "upgrade", "proxy-authorization",
			"proxy-connection", "forwarded", "x-app-request");

	// Each other upstream header is removed. Set-Cookie, Access-Control-*, and WWW-Authenticate would act on
	// the origin of this application.
	private static final Set<String> RELAYED_RESPONSE_HEADERS = Set.of("content-type", "content-length",
			"content-encoding", "content-language", "content-disposition", "content-location", "content-range",
			"accept-ranges", "location", "etag", "last-modified", "cache-control", "expires", "pragma", "vary",
			"date", "retry-after", "link", "x-request-id", "x-progress");

	private final List<URI> listedBases;
	private final RestClient listed;
	private final RestClient publicOnly;
	private final boolean enabled;
	private final boolean passthrough;

	public FrontendProxyController(
			FhirServers fhirServers,
			ClientHttpRequestFactorySettings settings,
			@Value("${app.proxy.enabled:false}") boolean enabled,
			@Value("${app.proxy.passthrough:false}") boolean passthrough,
			@Value("${app.proxy.connect-timeout:5s}") Duration connectTimeout,
			@Value("${app.proxy.read-timeout:60s}") Duration readTimeout) {
		// A root-relative entry is on the origin of the page, so the frontend requests it directly and it is never
		// a proxy destination. It has no scheme or host to classify a destination against.
		this.listedBases = fhirServers.entries().stream()
				.filter(entry -> !entry.url().startsWith("/"))
				.map(entry -> URI.create(entry.url()))
				.toList();
		ClientHttpRequestFactorySettings timeouts = settings.withConnectTimeout(connectTimeout).withReadTimeout(readTimeout);
		// A new builder carries no customizer of the application, so no interceptor buffers a body and no
		// initializer adds a header.
		this.listed = RestClient.builder().requestFactory(ProxyHttpClients.listed(timeouts)).build();
		this.publicOnly = RestClient.builder().requestFactory(ProxyHttpClients.publicOnly(timeouts)).build();
		this.enabled = enabled;
		this.passthrough = passthrough;
	}

	@RequestMapping("/proxy")
	public void proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
		harden(response);
		if (!enabled) {
			refuse(response, 404, "The proxy is not enabled");
			return;
		}
		if (!FORWARDED_METHODS.contains(request.getMethod())) {
			refuse(response, 405, "The proxy forwards only " + METHODS);
			response.setHeader(HttpHeaders.ALLOW, METHODS);
			return;
		}
		ProxyDestinations.Decision decision;
		try {
			URI destination = ProxyDestinations.parse(ProxyDestinations.urlValue(request.getQueryString()));
			decision = ProxyDestinations.classify(destination, listedBases, passthrough);
		} catch (IllegalArgumentException e) {
			refuse(response, 400, e.getMessage());
			return;
		}
		RestClient client = switch (decision.route()) {
			case LISTED -> listed;
			case PASSTHROUGH -> publicOnly;
			case REFUSED -> null;
		};
		if (client == null) {
			refuse(response, 403, "The destination is not under a listed server, and passthrough is off");
			return;
		}
		try {
			// FormContentFilter reads a form-encoded PUT, PATCH, or DELETE body, and the multipart resolver reads a
			// multipart body, before the controller runs. Such a request goes nowhere, because an upstream can act
			// on a request head with no body.
			if (declaresBody(request) && bodyAlreadyRead(request)) {
				refuse(response, 400, INCOMPLETE_BODY);
				return;
			}
			forward(client, decision.upstream(), request, response);
		} catch (IOException | RuntimeException e) {
			if (response.isCommitted()) {
				// The status line is already sent. Only a broken connection tells the client that the body is
				// incomplete.
				throw e;
			}
			int status = statusFor(e);
			URI upstream = decision.upstream();
			// The log leaves out the path and the query, which can hold patient data. A 400 or 403 comes from the
			// request itself, for example a server URL from thin mode in browser storage, so its level is INFO.
			log.atLevel(status >= 500 ? Level.WARN : Level.INFO).log("The proxy answered {} for a {} request to {}://{}: {}",
					status, decision.route(), upstream.getScheme(), upstream.getRawAuthority(),
					NestedExceptionUtils.getMostSpecificCause(e).toString());
			// The reasons are fixed, because an exception message can name an internal host or address.
			refuse(response, status, switch (status) {
				case 400 -> INCOMPLETE_BODY;
				case 403 -> GUARD_REFUSAL;
				case 504 -> "The upstream server did not answer in time";
				default -> "The connection to the upstream server failed";
			});
		}
	}

	// Spring answers a plain OPTIONS request itself unless a mapping names the method. A preflight request goes
	// to the CORS configuration.
	@RequestMapping(path = "/proxy", method = RequestMethod.OPTIONS)
	public void options(HttpServletRequest request, HttpServletResponse response) throws IOException {
		proxy(request, response);
	}

	static int statusFor(Throwable failure) {
		if (hasCause(failure, BlockedAddressException.class)) {
			return 403;
		}
		if (hasCause(failure, IncompleteBodyException.class)) {
			return 400;
		}
		// ConnectTimeoutException of Apache HttpClient is a SocketTimeoutException.
		return hasCause(failure, SocketTimeoutException.class) ? 504 : 502;
	}

	private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (type.isInstance(cause)) {
				return true;
			}
		}
		return false;
	}

	private static void forward(RestClient client, URI upstream, HttpServletRequest request, HttpServletResponse response)
			throws IOException {
		RestClient.RequestBodySpec spec = client.method(HttpMethod.valueOf(request.getMethod()))
				.uri(upstream)
				.headers(headers -> {
					for (String name : Collections.list(request.getHeaderNames())) {
						if (isForwarded(name)) {
							headers.addAll(name, Collections.list(request.getHeaders(name)));
						}
					}
				});
		if (declaresBody(request)) {
			spec.body(out -> {
				long declared = request.getContentLengthLong();
				long sent = request.getInputStream().transferTo(out);
				// A request wrapper can give fewer bytes than its Content-Length, for example after a filter read part
				// of the body. A body shorter than its Content-Length would put the next request on the pooled
				// connection out of step. The client closes the connection on this exception.
				if (declared >= 0 && sent != declared) {
					throw new IncompleteBodyException(sent, declared);
				}
			});
		}
		// The relay runs outside the exchange, so an IOException from the browser connection is not wrapped as
		// an upstream failure.
		try (ClientHttpResponse upstreamResponse = spec.exchange((clientRequest, clientResponse) -> clientResponse, false)) {
			relay(upstreamResponse, response, HttpMethod.HEAD.matches(request.getMethod()));
		}
	}

	// getInputStream throws after a filter called getReader, which takes the body as characters.
	private static boolean bodyAlreadyRead(HttpServletRequest request) throws IOException {
		try {
			return request.getInputStream().isFinished();
		} catch (IllegalStateException e) {
			return true;
		}
	}

	private static boolean declaresBody(HttpServletRequest request) {
		return request.getContentLengthLong() > 0 || request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null;
	}

	private static boolean isForwarded(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		return !DENIED_REQUEST_HEADERS.contains(lower) && !lower.startsWith("x-forwarded-") && !lower.startsWith("sec-");
	}

	private static void relay(ClientHttpResponse upstream, HttpServletResponse response, boolean head) throws IOException {
		int status = upstream.getStatusCode().value();
		String location = upstream.getHeaders().getFirst(HttpHeaders.LOCATION);
		if (status >= 300 && status < 400 && location != null) {
			refuse(response, 502, "The upstream server answered " + status + " with a redirect to " + location
					+ ". The proxy does not follow redirects.");
			return;
		}
		response.setStatus(status);
		upstream.getHeaders().forEach((name, values) -> {
			String lower = name.toLowerCase(Locale.ROOT);
			// Only a HEAD answer gets the upstream Content-Length, so a body that the upstream ends early cannot look
			// complete to an HTTP/1.1 client. With a relayed Content-Length, the container fills the missing tail with
			// its error page.
			if (RELAYED_RESPONSE_HEADERS.contains(lower) && (head || !"content-length".equals(lower))) {
				values.forEach(value -> response.addHeader(name, value));
			}
		});
		InputStream body = upstream.getBody();
		OutputStream out = response.getOutputStream();
		byte[] buffer = new byte[8192];
		for (int read = body.read(buffer); read != -1; read = body.read(buffer)) {
			out.write(buffer, 0, read);
			// Each part of a slow upstream response goes to the browser when it arrives.
			out.flush();
		}
	}

	// A request body with fewer bytes than its Content-Length.
	private static final class IncompleteBodyException extends IOException {
		private static final long serialVersionUID = 1L;

		IncompleteBodyException(long sent, long declared) {
			super("The request body has " + sent + " of " + declared + " bytes");
		}
	}

	private static void harden(HttpServletResponse response) {
		response.setHeader("X-Content-Type-Options", "nosniff");
		response.setHeader("Content-Security-Policy", "sandbox");
	}

	private static void refuse(HttpServletResponse response, int status, String reason) throws IOException {
		response.reset();
		harden(response);
		response.setStatus(status);
		response.setContentType("text/plain;charset=UTF-8");
		response.getOutputStream().write(reason.getBytes(StandardCharsets.UTF_8));
	}
}
