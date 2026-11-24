package com.lantanagroup.pdex.proxy;

import com.lantanagroup.pdex.proxy.ProxyDestinations.Decision;
import com.lantanagroup.pdex.proxy.ProxyDestinations.Route;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProxyDestinationsTest {

	private static final String HOST = "https://a.example";
	private static final List<String> BASES = List.of(HOST + "/fhir", HOST + "/fhir/");

	@Test
	void parseAcceptsHttpAndHttps() {
		assertEquals("http://a.example/fhir/Patient", ProxyDestinations.parse("http://a.example/fhir/Patient").toString());
		assertEquals("https://a.example:8443/fhir", ProxyDestinations.parse("https://a.example:8443/fhir").toString());
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", "/fhir/Patient", "ftp://a.example/x", "file:///etc/passwd", "https://u:p@a.example/fhir",
			"https://@a.example/fhir"})
	void parseRefuses(String destination) {
		assertThrows(IllegalArgumentException.class, () -> ProxyDestinations.parse(destination));
	}

	// The last value has U+037E, the Greek question mark, which NFC in the ASCII conversion maps to a semicolon.
	@ParameterizedTest
	@ValueSource(strings = {"https://a.example/fhir/%2e%2e/admin", "https://a.example/fhir/..%2Fadmin",
			"https://a.example/fhir/..%5Cadmin", "https://a.example/fhir\\admin", "https://a.example/fhir/..;/actuator",
			"https://a.example/fhir/..\u037e/actuator"})
	void parseRefusesPathTricks(String destination) {
		assertThrows(IllegalArgumentException.class, () -> ProxyDestinations.parse(destination));
	}

	@Test
	void parseKeepsFhirQueryCharacters() {
		assertQueryMeaning("identifier=http://sys|123", "identifier=http://sys|123");
		assertQueryMeaning("name=Smith\\,John", "name=Smith\\,John");
		assertQueryMeaning("name=John Smith", "name=John Smith");
		assertQueryMeaning("subject=Patient%2F123", "subject=Patient/123");
		assertQueryMeaning("x=^{}`&y=100%", "x=^{}`&y=100%");
		assertEquals("subject=Patient%2F123", ProxyDestinations.parse(HOST + "/fhir/Observation?subject=Patient%2F123").getRawQuery());
	}

	@Test
	void parseReturnsTheAsciiForm() {
		URI query = ProxyDestinations.parse(HOST + "/fhir/Patient?name=Jos\u00e9");
		assertEquals("name=Jos%C3%A9", query.getRawQuery());
		assertEquals("name=Jos\u00e9", query.getQuery());
		assertEquals("/fhir/Patient/Jos%C3%A9", ProxyDestinations.parse(HOST + "/fhir/Patient/Jos\u00e9").getRawPath());
	}

	@ParameterizedTest
	@ValueSource(strings = {"/fhir/Patient", "/fhir", "/fhir/", "/fhir?x=1", "/fhir/Observation?subject=Patient%2F123"})
	void classifiesUnderListedBase(String pathAndQuery) {
		for (String base : BASES) {
			Decision decision = classify(HOST + pathAndQuery, base, false);
			assertEquals(Route.LISTED, decision.route(), base);
			assertEquals(HOST + pathAndQuery, decision.upstream().toString(), base);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"/fhirx/Patient", "/fhir/../admin", "/other"})
	void classifiesOutsideBase(String pathAndQuery) {
		for (String base : BASES) {
			Decision passthrough = classify(HOST + pathAndQuery, base, true);
			assertEquals(Route.PASSTHROUGH, passthrough.route(), base);
			assertEquals(ProxyDestinations.parse(HOST + pathAndQuery).toString(), passthrough.upstream().toString(), base);
			Decision refused = classify(HOST + pathAndQuery, base, false);
			assertEquals(Route.REFUSED, refused.route(), base);
			assertNull(refused.upstream(), base);
		}
	}

	@Test
	void rootBaseCoversTheOrigin() {
		for (String base : List.of(HOST, HOST + "/")) {
			for (String destination : List.of(HOST + "/Patient", HOST + "?x=1")) {
				Decision decision = classify(destination, base, false);
				assertEquals(Route.LISTED, decision.route(), base);
				assertEquals(destination, decision.upstream().toString(), base);
			}
		}
	}

	@Test
	void listedMatchIgnoresHostCaseAndDefaultPort() {
		assertEquals(Route.LISTED, classify("HTTPS://A.EXAMPLE:443/fhir/Patient", HOST + "/fhir", false).route());
		assertEquals(Route.LISTED, classify(HOST + "/fhir/Patient", "https://a.example:443/fhir", false).route());
		assertEquals(Route.LISTED, classify("http://a.example:80/fhir/Patient", "http://a.example/fhir", false).route());
		assertEquals(Route.REFUSED, classify("https://a.example:8443/fhir/Patient", HOST + "/fhir", false).route());
		assertEquals(Route.REFUSED, classify("http://a.example/fhir/Patient", HOST + "/fhir", false).route());
		assertEquals(Route.REFUSED, classify("http://a.example:443/fhir/Patient", HOST + "/fhir", false).route());
		assertEquals(Route.REFUSED, classify("https://b.example/fhir/Patient", HOST + "/fhir", false).route());
	}

	@Test
	void listedUpstreamUsesConfiguredAuthority() {
		assertEquals("https://a.example:8443/fhir/Patient?x=1",
				classify("HTTPS://A.EXAMPLE:8443/fhir/Patient?x=1", "https://a.example:8443/fhir", false).upstream().toString());
		assertEquals("https://a.example/fhir/Patient",
				classify("HTTPS://A.Example:443/fhir/Patient", HOST + "/fhir", false).upstream().toString());
		assertEquals("http://[::1]:8080/fhir/Patient",
				classify("http://[::1]:8080/fhir/Patient", "http://[::1]:8080/fhir", false).upstream().toString());
	}

	@Test
	void internalLiteralFormsNeverPass() {
		for (String destination : List.of("http://127.1:1/", "http://0x7f.0.0.1:1/", "http://2130706433:1/")) {
			URI parsed;
			try {
				parsed = ProxyDestinations.parse(destination);
			} catch (IllegalArgumentException refused) {
				continue;
			}
			assertThrows(UnknownHostException.class, () -> PublicAddresses.resolve(parsed.getHost()), destination);
		}
	}

	private static Decision classify(String destination, String base, boolean passthrough) {
		return ProxyDestinations.classify(ProxyDestinations.parse(destination), List.of(URI.create(base)), passthrough);
	}

	private static void assertQueryMeaning(String query, String meaning) {
		assertEquals(meaning, ProxyDestinations.parse(HOST + "/fhir/Patient?" + query).getQuery(), query);
	}
}
