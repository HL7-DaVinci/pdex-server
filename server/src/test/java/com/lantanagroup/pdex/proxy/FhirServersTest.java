package com.lantanagroup.pdex.proxy;

import com.lantanagroup.pdex.config.ConfigController;
import com.lantanagroup.pdex.config.FhirServers;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FhirServersTest {

	private static final String PROPERTY = "app.fhir.servers";
	private static final FhirServers.Entry SERVER_A = new FhirServers.Entry("A", "https://a.example/fhir");
	private static final String THIN_CONFIG =
			"window.APP_CONFIG = {\"fhirServers\":[{\"name\":\"A\",\"url\":\"https://a.example/fhir\"}]};";
	private static final String PROXY_CONFIG =
			"window.APP_CONFIG = {\"fhirServers\":[{\"name\":\"A\",\"url\":\"https://a.example/fhir\"}],\"proxy\":{\"passthrough\":false}};";

	@Test
	void readsListForm() {
		assertEquals(List.of(SERVER_A), listForm().entries());
	}

	@Test
	void readsStringForm() {
		assertEquals(listForm().entries(), stringForm("[{\"name\":\"A\",\"url\":\"https://a.example/fhir\"}]").entries());
	}

	@Test
	void readsLooseStringForm() {
		assertEquals(List.of(SERVER_A), stringForm("[{name:'A',url:'https://a.example/fhir',}]").entries());
	}

	@Test
	void ignoresUnknownFields() {
		assertEquals(List.of(SERVER_A), withSecret().entries());
		assertEquals(List.of(SERVER_A), new FhirServers(listFormEnvironment().withProperty(PROPERTY + "[0].secret", "s3")).entries());
	}

	@Test
	void emptyOrAbsentGivesNoEntries() {
		assertEquals(List.of(), new FhirServers(new MockEnvironment()).entries());
		assertEquals(List.of(), stringForm("").entries());
		assertEquals(List.of(), stringForm("  ").entries());
		assertEquals(List.of(), stringForm("[]").entries());
	}

	@Test
	void refusesBrokenString() {
		for (String value : List.of("[{", "{}", "null", "[]]")) {
			assertRefused(stringFormEnvironment(value));
		}
		assertRefused(new MockEnvironment().withProperty(PROPERTY + "[0]", "A"));
	}

	@Test
	void refusesEntryWithoutUrl() {
		for (String value : List.of(
				"[{\"name\":\"A\"}]",
				"[{\"name\":\"A\",\"url\":\" \"}]",
				"[{\"url\":\"https://a.example/fhir\"}]",
				"[{\"name\":\"\",\"url\":\"https://a.example/fhir\"}]",
				"[null]")) {
			assertRefused(stringFormEnvironment(value));
		}
		assertRefused(new MockEnvironment().withProperty(PROPERTY + "[0].name", "A"));
		assertRefused(new MockEnvironment().withProperty(PROPERTY + "[0].url", "https://a.example/fhir"));
	}

	@Test
	void refusesBadUrls() {
		for (String url : List.of(
				"ftp://a/fhir",
				"https://u:p@a/fhir",
				"https://a/fhir?x=1",
				"https://a/fhir#f",
				"http:/fhir",
				"https://a/my fhir",
				"https://hapi_server/fhir",
				"https://u:p@hapi_server/fhir")) {
			assertRefused(stringFormEnvironment("[{\"name\":\"A\",\"url\":\"" + url + "\"}]"));
		}
	}

	@Test
	void acceptsRootRelativeUrl() {
		List<FhirServers.Entry> local = List.of(new FhirServers.Entry("Local", "/fhir"));
		assertEquals(local, stringForm("[{\"name\":\"Local\",\"url\":\"/fhir\"}]").entries());
		assertEquals(local, new FhirServers(listFormEnvironment("Local", "/fhir")).entries());
		for (String url : List.of("/", "//host/fhir", "/fhir?x=1", "/fhir#f", "fhir", "/fhir\\x")) {
			assertRefused(listFormEnvironment("Local", url));
		}
	}

	@Test
	void configOutputInThinMode() throws Exception {
		assertEquals(THIN_CONFIG, new ConfigController(listForm(), false, false, false, false).getConfig());
		assertEquals(THIN_CONFIG, new ConfigController(withSecret(), false, true, false, false).getConfig());
		assertEquals("window.APP_CONFIG = {\"fhirServers\":[]};",
				new ConfigController(new FhirServers(new MockEnvironment()), false, false, false, false).getConfig());
		assertEquals("window.APP_CONFIG = {\"fhirServers\":[{\"name\":\"Local\",\"url\":\"/fhir\"}]};",
				new ConfigController(new FhirServers(listFormEnvironment("Local", "/fhir")), false, false, false, false).getConfig());
	}

	@Test
	void configOutputInProxyMode() throws Exception {
		String config = new ConfigController(withSecret(), true, false, false, false).getConfig();
		assertEquals(PROXY_CONFIG, config);
		assertFalse(config.contains("s3"));
		assertEquals(
				"window.APP_CONFIG = {\"fhirServers\":[{\"name\":\"A\",\"url\":\"https://a.example/fhir\"}],\"proxy\":{\"passthrough\":true}};",
				new ConfigController(listForm(), true, true, false, false).getConfig());
		assertEquals(
				"window.APP_CONFIG = {\"fhirServers\":[{\"name\":\"A\",\"url\":\"https://a.example/fhir\"}],\"proxy\":{\"passthrough\":true},\"login\":{}};",
				new ConfigController(listForm(), true, true, true, false).getConfig());
		assertEquals(
				"window.APP_CONFIG = {\"fhirServers\":[{\"name\":\"A\",\"url\":\"https://a.example/fhir\"}],\"proxy\":{\"passthrough\":true},\"login\":{\"people\":true}};",
				new ConfigController(listForm(), true, true, true, true).getConfig());
	}

	private static MockEnvironment listFormEnvironment() {
		return listFormEnvironment("A", "https://a.example/fhir");
	}

	private static MockEnvironment listFormEnvironment(String name, String url) {
		return new MockEnvironment()
				.withProperty(PROPERTY + "[0].name", name)
				.withProperty(PROPERTY + "[0].url", url);
	}

	private static MockEnvironment stringFormEnvironment(String value) {
		return new MockEnvironment().withProperty(PROPERTY, value);
	}

	private static FhirServers listForm() {
		return new FhirServers(listFormEnvironment());
	}

	private static FhirServers stringForm(String value) {
		return new FhirServers(stringFormEnvironment(value));
	}

	private static FhirServers withSecret() {
		return stringForm("[{\"name\":\"A\",\"url\":\"https://a.example/fhir\",\"secret\":\"s3\"}]");
	}

	private static void assertRefused(MockEnvironment environment) {
		IllegalStateException error = assertThrows(IllegalStateException.class, () -> new FhirServers(environment));
		assertTrue(error.getMessage().contains(PROPERTY), error.getMessage());
	}
}
