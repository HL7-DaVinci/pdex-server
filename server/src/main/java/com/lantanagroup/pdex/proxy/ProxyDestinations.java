package com.lantanagroup.pdex.proxy;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.util.UriUtils;

/**
 * The URL rules of the proxy and the classification of a destination against the listed servers.
 */
public final class ProxyDestinations {

	private ProxyDestinations() {}

	public enum Route { LISTED, PASSTHROUGH, REFUSED }

	/**
	 * For LISTED, the upstream has the scheme, host, and port of the listed server and the path and query
	 * of the destination. For PASSTHROUGH, the upstream is the destination. For REFUSED, it is null.
	 */
	public record Decision(Route route, URI upstream) {}

	// With space and control characters, these are the characters that java.net.URI refuses in a query.
	// A browser can send them raw, and FHIR search uses "|" in tokens and "\" as an escape.
	private static final String REFUSED_IN_QUERY = " \"<>\\^`{|}";

	/**
	 * Parses the decoded url value into an absolute http or https URL in ASCII form, with a host, no user
	 * information, and a normalized path.
	 *
	 * @throws IllegalArgumentException with the reason, for a value that the proxy refuses
	 */
	public static URI parse(String destination) {
		if (destination == null) {
			throw new IllegalArgumentException("The url value is missing");
		}
		// The checks below run on the ASCII form, which is the form that the upstream receives. toASCIIString
		// applies NFC first, and NFC maps some characters to ASCII, for example U+037E to a semicolon.
		URI uri;
		try {
			uri = new URI(new URI(encodeRefusedQueryCharacters(destination)).toASCIIString());
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("The url value is not a valid URL: " + e.getReason());
		}
		if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
			throw new IllegalArgumentException("The url value must be an absolute http or https URL");
		}
		if (uri.getHost() == null) {
			throw new IllegalArgumentException("The url value must have a valid host");
		}
		if (uri.getRawUserInfo() != null) {
			throw new IllegalArgumentException("The url value must not have user information");
		}
		// An upstream server can read these as path separators, dot segments, or path parameters.
		String path = uri.getRawPath().toLowerCase(Locale.ROOT);
		if (path.contains("%2e") || path.contains("%2f") || path.contains("%5c") || path.contains("\\")
				|| path.contains(";")) {
			throw new IllegalArgumentException(
					"The url path must not contain %2e, %2f, %5c, a backslash, or a semicolon");
		}
		return uri.normalize();
	}

	/**
	 * Classifies a destination from {@link #parse}. A destination is under a listed server when the
	 * scheme, host, and port are equal and the path is the base path or starts with the base path plus "/".
	 */
	public static Decision classify(URI destination, List<URI> listedBases, boolean passthrough) {
		URI target = destination.normalize();
		for (URI base : listedBases) {
			if (isUnder(target, base)) {
				return new Decision(Route.LISTED, upstream(base, target));
			}
		}
		return passthrough ? new Decision(Route.PASSTHROUGH, target) : new Decision(Route.REFUSED, null);
	}

	// Raw paths are compared, and parse returns the ASCII form, so the check sees the same bytes that the
	// upstream receives.
	public static boolean isUnder(URI target, URI base) {
		String basePath = StringUtils.stripEnd(base.normalize().getRawPath(), "/");
		String path = target.getRawPath();
		return target.getScheme().equalsIgnoreCase(base.getScheme())
				&& target.getHost().equalsIgnoreCase(base.getHost())
				&& effectivePort(target) == effectivePort(base)
				&& (path.equals(basePath) || path.startsWith(basePath + "/"));
	}

	// The value comes from the query string only. getParameter would read a form body.
	public static String urlValue(String query) {
		if (query == null) {
			return null;
		}
		for (String pair : query.split("&")) {
			if (pair.startsWith("url=")) {
				try {
					return UriUtils.decode(pair.substring(4), StandardCharsets.UTF_8);
				} catch (IllegalArgumentException e) {
					throw new IllegalArgumentException("The url value has an invalid percent-encoding");
				}
			}
		}
		return null;
	}

	private static int effectivePort(URI uri) {
		if (uri.getPort() != -1) {
			return uri.getPort();
		}
		return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
	}

	// The authority comes from the configured URL only, so a parser trick in the destination cannot move
	// the client that has no guard to a different host. The multi-argument URI constructors encode each
	// "%", so the raw parts are joined into one string.
	private static URI upstream(URI base, URI target) {
		String port = base.getPort() == -1 ? "" : ":" + base.getPort();
		String query = target.getRawQuery() == null ? "" : "?" + target.getRawQuery();
		return URI.create(base.getScheme() + "://" + base.getHost() + port + target.getRawPath() + query);
	}

	// Encodes each character of the query that java.net.URI refuses, and keeps valid escapes such as %2F.
	// The query starts at the first "?" and ends at the first "#".
	private static String encodeRefusedQueryCharacters(String destination) {
		int hash = destination.indexOf('#');
		int end = hash == -1 ? destination.length() : hash;
		int start = destination.indexOf('?');
		if (start == -1 || start > end) {
			return destination;
		}
		StringBuilder encoded = new StringBuilder(destination.substring(0, start + 1));
		for (int i = start + 1; i < end; i++) {
			char c = destination.charAt(i);
			if (isRefusedInQuery(destination, i, end)) {
				for (byte b : String.valueOf(c).getBytes(StandardCharsets.UTF_8)) {
					encoded.append(String.format("%%%02X", b & 0xff));
				}
			} else {
				encoded.append(c);
			}
		}
		return encoded.append(destination.substring(end)).toString();
	}

	private static boolean isRefusedInQuery(String text, int index, int end) {
		char c = text.charAt(index);
		if (c == '%') {
			return index + 2 >= end || !isHex(text.charAt(index + 1)) || !isHex(text.charAt(index + 2));
		}
		return REFUSED_IN_QUERY.indexOf(c) >= 0 || Character.isSpaceChar(c) || Character.isISOControl(c);
	}

	private static boolean isHex(char c) {
		return "0123456789abcdefABCDEF".indexOf(c) >= 0;
	}
}
