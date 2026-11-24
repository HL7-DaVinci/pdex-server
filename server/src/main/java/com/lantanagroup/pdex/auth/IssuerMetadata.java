package com.lantanagroup.pdex.auth;

import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jose.util.ResourceRetriever;
import java.io.IOException;
import java.net.URI;
import java.text.ParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads the documents under {issuer}/.well-known/ of app.auth.issuer and keeps each document after its first
 * successful read. It owns the HTTP retriever of the issuer documents and the key set.
 */
@Component
public class IssuerMetadata {

	// The timeouts allow for a slow issuer.
	static final int CONNECT_TIMEOUT_MS = 5000;
	static final int READ_TIMEOUT_MS = 10000;

	private final String issuer;

	/** Checks the certificate chain and the host name for each call that the auth features make. Absent means true. */
	private final boolean sslVerify;

	private final ResourceRetriever retriever;
	private final Map<String, Map<String, Object>> documents = new ConcurrentHashMap<>();

	/** With app.ssl-verify false, the retriever accepts any certificate and any host name. */
	public IssuerMetadata(AuthResourceProperties properties, @Value("${app.ssl-verify:true}") boolean sslVerify) {
		this.issuer = properties.getIssuer();
		this.sslVerify = sslVerify;
		this.retriever = new DefaultResourceRetriever(
				CONNECT_TIMEOUT_MS,
				READ_TIMEOUT_MS,
				JWKSourceBuilder.DEFAULT_HTTP_SIZE_LIMIT,
				true,
				sslVerify ? null : TrustAllSslContext.create().getSocketFactory());
	}

	/** The configured issuer, or null. */
	public String issuer() {
		return issuer;
	}

	/** The value of app.ssl-verify. */
	public boolean sslVerify() {
		return sslVerify;
	}

	public ResourceRetriever retriever() {
		return retriever;
	}

	/**
	 * Returns the JSON object at {issuer}/.well-known/{name}. A failed read keeps nothing, so the next call reads
	 * again.
	 *
	 * @throws IllegalStateException when the issuer is blank, the read fails, or the body is not a JSON object
	 */
	public Map<String, Object> wellKnown(String name) {
		Map<String, Object> document = documents.get(name);
		if (document != null) {
			return document;
		}
		String url = Strings.CS.removeEnd(Objects.toString(issuer, ""), "/") + "/.well-known/" + name;
		if (StringUtils.isBlank(issuer)) {
			throw new IllegalStateException("Could not read " + url + ": app.auth.issuer has no value");
		}
		try {
			document = JSONObjectUtils.parse(retriever.retrieveResource(URI.create(url).toURL()).getContent());
		} catch (IOException | ParseException | IllegalArgumentException e) {
			throw new IllegalStateException("Could not read " + url + ": " + e.getMessage(), e);
		}
		// JSONObjectUtils.parse returns null for the JSON literal null.
		if (document == null) {
			throw new IllegalStateException("Could not read " + url + ": the body is not a JSON object");
		}
		documents.put(name, document);
		return document;
	}

	/** Returns the member when it is a string with text, or null. */
	public static String string(Map<String, Object> document, String member) {
		return document.get(member) instanceof String value && StringUtils.isNotBlank(value) ? value : null;
	}

	/** Returns the member when it is a list that holds only strings, or null. */
	public static List<String> stringList(Map<String, Object> document, String member) {
		if (!(document.get(member) instanceof List<?> list) || !list.stream().allMatch(String.class::isInstance)) {
			return null;
		}
		return list.stream().map(String.class::cast).toList();
	}
}
