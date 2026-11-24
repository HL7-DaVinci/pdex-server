package com.lantanagroup.pdex.scopes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ca.uhn.fhir.jpa.starter.Application;
import com.lantanagroup.pdex.auth.TestIssuer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jwt.JWTClaimsSet;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots the full server with the bearer token check and the scope check on. TestIssuer serves the keys of the
 * issuer. Each test makes its own data with the bypass header, because the tests share one database.
 */
@ActiveProfiles("test")
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		classes = {Application.class},
		properties = {
			"spring.datasource.url=jdbc:h2:mem:authscopesit",
			// Sets the FHIR version of the server project. The application-test.yaml of the starter has R4.
			"hapi.fhir.fhir_version=R4",
			"app.auth.enabled=true",
			"app.auth.scopes.enabled=true",
			"app.auth.bypass-header=X-Bypass-Auth",
			"spring.ai.mcp.server.enabled=false",
			"spring.main.allow-bean-definition-overriding=true",
			"management.health.elasticsearch.enabled=false",
			"spring.jpa.properties.hibernate.search.backend.directory.type=local-heap"
		})
class AuthScopesIT {

	private static final String BYPASS_HEADER = "X-Bypass-Auth";
	private static final String PATIENT = "{\"resourceType\":\"Patient\"}";
	private static final String IDENTIFIER_SYSTEM = "http://example.org/scopes-it";
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
	private static final TestIssuer ISSUER = new TestIssuer();

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void issuer(DynamicPropertyRegistry registry) {
		registry.add("app.auth.issuer", ISSUER::url);
		registry.add("app.auth.jwks-uri", () -> ISSUER.url() + "/jwks");
	}

	@AfterAll
	static void stopIssuer() {
		ISSUER.stop();
	}

	@Test
	void lettersDecideDirectRequests() throws Exception {
		String patient = "Patient/" + create("Patient", PATIENT);
		String body = patientWithId(patient);

		expect(200, "user/Patient.r", "GET", patient, null);
		expect(403, "user/Patient.r", "GET", "Patient", null);
		expect(200, "user/Patient.s", "GET", "Patient", null);
		expect(403, "user/Patient.s", "GET", patient, null);
		expect(201, "user/Patient.c", "POST", "Patient", PATIENT);
		expect(403, "user/Patient.c", "PUT", patient, body);
		expect(403, "user/Observation.u user/Patient.c", "PUT", patient, body);
		expect(200, "user/Patient.u", "PUT", patient, body);
		expect(201, "user/Patient.u", "PUT", "Patient/scopes-it-new", patientWithId("Patient/scopes-it-new"));
		expect(403, "user/Patient.u", "POST", "Patient", PATIENT);
		expect(403, "user/Patient.u", "DELETE", patient, null);
		expect(200, "user/Patient.d", "DELETE", patient, null);

		String subject = create("Patient", identifiedPatient("letters"));
		create("Observation", identifiedObservation(subject, "letters"));
		String match = "identifier=" + IDENTIFIER_SYSTEM + "%7Cletters";
		String noMatch = "identifier=" + IDENTIFIER_SYSTEM + "%7Cletters-none";
		String matchingObservation = identifiedObservation(subject, "letters");
		String otherObservation = identifiedObservation(subject, "letters-none");
		expect(403, "user/Observation.c", null, "POST", "Observation", matchingObservation, "If-None-Exist", match);
		expect(403, "user/Observation.c", null, "POST", "Observation", otherObservation, "If-None-Exist", noMatch);
		expect(403, "user/Observation.d", "DELETE", "Observation?" + match, null);
		expect(403, "user/Observation.d", "DELETE", "Observation?" + noMatch, null);
		String reference = "Patient?identifier=" + IDENTIFIER_SYSTEM + "|letters";
		expect(403, "user/Observation.c", "POST", "Observation", observation(reference));
		expect(403, "user/Observation.c", "POST", "Observation", observation(reference + "-none"));
		expect(201, "user/Observation.c user/Patient.s", "POST", "Observation", observation(reference));
		expect(200, "user/Observation.cs", null, "POST", "Observation", matchingObservation, "If-None-Exist", match);
		expect(201, "user/Observation.cs", null, "POST", "Observation", otherObservation, "If-None-Exist", noMatch);

		HttpResponse<String> refused = expect(403, "user/Patient.rs", "GET", "Observation?_id=no-match", null);
		assertEquals(Optional.of("Bearer error=\"insufficient_scope\""), refused.headers().firstValue("WWW-Authenticate"));
		assertEquals("forbidden", JSON.readTree(refused.body()).at("/issue/0/code").asText());
	}

	@Test
	void readsEachScopeForm() throws Exception {
		for (Object scope : List.of(
				"user/Patient.read",
				"user/*.rs",
				"system/Patient.*",
				"openid system/Patient.rs",
				List.of("openid", "system/Patient.rs"))) {
			expect(200, scope, "GET", "Patient", null);
		}
		for (Object scope : Arrays.asList(
				"user/Patient.rs?_id=1", "openid", null, "user/Foo.rs system/Observation.rs")) {
			expect(403, scope, "GET", "Patient", null);
		}
		expect(200, "user/Foo.rs system/Observation.rs", "GET", "Observation", null);
		String emptyHistory = "_history?_since=2100-01-01T00:00:00Z";
		expect(403, "user/Foo.rs", "GET", emptyHistory, null);
		expect(200, "user/Patient.rs", "GET", emptyHistory, null);
	}

	@Test
	void checksIncludedResources() throws Exception {
		String patient = create("Patient", PATIENT);
		create("Observation", observation("Patient/" + patient));
		String search = "Patient?_id=" + patient + "&_revinclude=Observation:subject";

		expect(403, "user/Patient.rs", "GET", search, null);
		expect(200, "user/Patient.rs user/Observation.s", "GET", search, null);
	}

	@Test
	void checksBundleEntries() throws Exception {
		String fullUrl = "urn:uuid:2b6f1c86-4f4e-4c55-9a43-6a1d1f0e8a01";
		String transaction = "{\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":["
				+ "{\"fullUrl\":\"" + fullUrl + "\",\"resource\":" + PATIENT
				+ ",\"request\":{\"method\":\"POST\",\"url\":\"Patient\"}},"
				+ "{\"resource\":" + observation(fullUrl) + ",\"request\":{\"method\":\"POST\",\"url\":\"Observation\"}}]}";
		int patients = count("Patient?_summary=count");

		expect(403, "user/Patient.cruds", "POST", "", transaction);
		assertEquals(patients, count("Patient?_summary=count"));
		expect(403, "user/Patient.c", "POST", "", conditionalCreate("transaction", "Patient", identifiedPatient("entry"),
				"Patient?identifier=" + IDENTIFIER_SYSTEM + "|entry"));
		expect(403, "user/Patient.c", "POST", "", conditionalCreate("transaction", "Patient/entry",
				identifiedPatient("entry"), "Patient?identifier=" + IDENTIFIER_SYSTEM + "|entry"));
		assertEquals(patients, count("Patient?_summary=count"));

		String patient = "Patient/" + create("Patient", PATIENT);
		String observation = "Observation/" + create("Observation", observation(patient));
		String batch = "{\"resourceType\":\"Bundle\",\"type\":\"batch\",\"entry\":["
				+ "{\"request\":{\"method\":\"GET\",\"url\":\"" + patient + "\"}},"
				+ "{\"request\":{\"method\":\"GET\",\"url\":\"" + observation + "\"}}]}";

		JsonNode entries = JSON.readTree(expect(200, "user/Patient.rs", "POST", "", batch).body()).get("entry");
		assertEquals("200", entryStatus(entries.get(0)));
		assertEquals("403", entryStatus(entries.get(1)));

		String searchBatch = "{\"resourceType\":\"Bundle\",\"type\":\"batch\",\"entry\":["
				+ "{\"request\":{\"method\":\"GET\",\"url\":\"Patient?_id=" + patient.substring("Patient/".length())
				+ "\"}}]}";
		JsonNode search = JSON.readTree(expect(200, "user/Patient.s", "POST", "", searchBatch).body()).get("entry");
		assertEquals("200", entryStatus(search.get(0)));
	}

	@Test
	void passesRequestsOutsideTheCheck() throws Exception {
		String patient = "Patient/" + create("Patient", PATIENT);

		expect(200, "user/Patient.r", "GET", patient + "/$meta", null);
		HttpResponse<String> metadata = send(request("GET", "metadata", null));
		assertEquals(200, metadata.statusCode(), metadata.body());
		HttpResponse<String> bypass = send(request("GET", "Observation", null).header(BYPASS_HEADER, "1"));
		assertEquals(200, bypass.statusCode(), bypass.body());
	}

	@Test
	void limitsPatientScopesToThePatientOfTheToken() throws Exception {
		String a = create("Patient", identifiedPatient("a-and-b"));
		String b = create("Patient", identifiedPatient("a-and-b"));
		String observationOfA = "Observation/" + create("Observation", observation("Patient/" + a));
		String observationOfB = "Observation/" + create("Observation", observation("Patient/" + b));
		String scopes = "patient/Patient.rs patient/Observation.rs";

		expect(200, scopes, a, "GET", observationOfA, null);
		expect(403, scopes, a, "GET", observationOfB, null);
		expect(200, scopes, a, "GET", "Observation?patient=" + a, null);
		assertEquals(
				Set.of(observationOfA),
				ids(expect(200, scopes, a, "GET", "Observation", null, "Cache-Control", "no-cache")));
		String patientsAAndB = "Patient?identifier=" + IDENTIFIER_SYSTEM + "%7Ca-and-b";
		assertEquals(
				Set.of("Patient/" + a),
				ids(expect(200, "patient/Patient.rs", a, "GET", patientsAAndB, null, "Cache-Control", "no-cache")));
		assertEquals(
				Set.of("Patient/" + a, "Patient/" + b),
				ids(expect(200, "patient/Observation.rs user/Patient.s", a, "GET", patientsAAndB, null,
						"Cache-Control", "no-cache")));
		assertEquals(
				Set.of(observationOfA),
				ids(expect(200, "patient/Observation.rs user/Patient.s", a, "GET", "Observation", null,
						"Cache-Control", "no-cache")));
		HttpResponse<String> otherPatient = expect(403, scopes, a, "GET", "Observation?patient=" + b, null);
		assertEquals(Optional.empty(), otherPatient.headers().firstValue("WWW-Authenticate"));
		JsonNode otherPatientIssue = JSON.readTree(otherPatient.body()).at("/issue/0");
		assertEquals("processing", otherPatientIssue.at("/code").asText());
		assertEquals("HAPI-2026: Value not permitted for parameter patient", otherPatientIssue.at("/diagnostics").asText());
		create("Observation", observation("Patient/" + b));
		int observationsOfA = count("Observation?patient=" + a + "&_summary=count");
		HttpResponse<String> countOfAAndB = expect(200, scopes, a, "GET",
				"Observation?patient=" + b + "," + a + "&_summary=count", null, "Cache-Control", "no-cache");
		assertEquals(observationsOfA, JSON.readTree(countOfAAndB.body()).get("total").asInt());
		JsonNode readOfA =
				JSON.readTree(expect(200, "patient/Patient.rs", a, "POST", "", batchGet("Patient/" + a)).body())
						.at("/entry/0");
		assertEquals("200", entryStatus(readOfA));
		assertEquals("Patient", readOfA.at("/resource/resourceType").asText());
		assertEquals(a, readOfA.at("/resource/id").asText());
		JsonNode searchEntry =
				JSON.readTree(expect(200, scopes, a, "POST", "", batchGet("Observation")).body()).at("/entry/0");
		assertEquals("403", entryStatus(searchEntry));
		expect(200, scopes, a, "GET", "Patient/" + a, null);
		expect(403, scopes, a, "GET", "Patient/" + b, null);
		expect(403, scopes, null, "GET", observationOfA, null);
		expect(200, "user/Observation.rs", a, "GET", observationOfB, null);
		expect(403, "patient/Observation.d", a, "DELETE", observationOfB, null);
		expect(403, "patient/Observation.d", a, "DELETE", "Encounter/scopes-it-unknown", null);
		expect(403, "patient/Patient.rs", a, "GET", "Observation?patient=" + a, null);
		expect(200, "patient/Observation.r patient/Observation.s", a, "GET", observationOfA, null);

		create("Observation", identifiedObservation(a, "own"));
		expect(403, "patient/Observation.us", a, "PUT", "Observation?identifier=" + IDENTIFIER_SYSTEM + "%7Cown",
				identifiedObservation(a, "own"));
		expect(403, "patient/Observation.u patient/Patient.c user/Observation.s", a, "POST", "",
				conditionalUpdate(a, "refused"));
		assertEquals(0, count("Observation?identifier=" + IDENTIFIER_SYSTEM + "%7Crefused&_summary=count"));
		HttpResponse<String> created =
				expect(200, "patient/Observation.cu user/Observation.s", a, "POST", "", conditionalUpdate(a, "allowed"));
		assertEquals("201", entryStatus(JSON.readTree(created.body()).at("/entry/0")));
		expect(201, "patient/Observation.c", a, "POST", "Observation", observation("Patient/" + a));
		HttpResponse<String> patientCreated = expect(200, "patient/Observation.c user/Patient.cs", a, "POST", "",
				conditionalCreate("transaction", "Patient", identifiedPatient("no-patient"),
						"Patient?identifier=" + IDENTIFIER_SYSTEM + "|no-patient"));
		assertEquals("201", entryStatus(JSON.readTree(patientCreated.body()).at("/entry/0")));
	}

	@Test
	void limitsOperationsOfPatientScopesToThePatientOfTheToken() throws Exception {
		String a = create("Patient", PATIENT);
		String b = create("Patient", PATIENT);

		expect(200, "patient/Patient.rs", a, "GET", "Patient/" + a + "/$everything", null);
		HttpResponse<String> typeLevel = expect(403, "patient/Patient.rs", a, "GET", "Patient/$everything", null);
		assertEquals(Optional.of("Bearer error=\"insufficient_scope\""), typeLevel.headers().firstValue("WWW-Authenticate"));
		expect(403, "patient/Patient.rs", a, "GET", "Patient/" + b + "/$everything", null);
		JsonNode entry =
				JSON.readTree(expect(200, "patient/Patient.rs", a, "POST", "", batchGet("Patient/$everything")).body())
						.at("/entry/0");
		assertEquals("403", entryStatus(entry));
		expect(200, "patient/Observation.rs user/Patient.rs", a, "GET", "Patient/$everything", null);
		expect(403, "user/Observation.rs", "GET", "Patient/$everything", null);
	}

	/**
	 * Sends the request with a token whose scope claim is the given value and checks the status. With a null scope, the
	 * token has no scope claim.
	 */
	private HttpResponse<String> expect(int status, Object scope, String method, String path, String body)
			throws Exception {
		return expect(status, scope, null, method, path, body);
	}

	/**
	 * Sends the request as the method above does. With a patient that is not null, the token has that patient claim.
	 * The headers are pairs of a name and a value.
	 */
	private HttpResponse<String> expect(
			int status, Object scope, String patient, String method, String path, String body, String... headers)
			throws Exception {
		HttpRequest.Builder request =
				request(method, path, body).header("Authorization", "Bearer " + token(scope, patient));
		for (int i = 0; i < headers.length; i += 2) {
			request.header(headers[i], headers[i + 1]);
		}
		HttpResponse<String> response = send(request);
		assertEquals(
				status,
				response.statusCode(),
				method + " " + path + " with " + scope + " and patient " + patient + ": " + response.body());
		return response;
	}

	/** Makes a resource with the bypass header and returns its id. */
	private String create(String type, String body) throws Exception {
		HttpResponse<String> response = send(request("POST", type, body).header(BYPASS_HEADER, "1"));
		assertEquals(201, response.statusCode(), response.body());
		return JSON.readTree(response.body()).get("id").asText();
	}

	/**
	 * Returns the total of a search with _summary=count, sent with the bypass header. The no-cache header stops the
	 * reuse of an earlier search.
	 */
	private int count(String search) throws Exception {
		HttpResponse<String> response = send(request("GET", search, null)
				.header(BYPASS_HEADER, "1")
				.header("Cache-Control", "no-cache"));
		assertEquals(200, response.statusCode(), response.body());
		return JSON.readTree(response.body()).get("total").asInt();
	}

	private static String patientWithId(String reference) {
		return "{\"resourceType\":\"Patient\",\"id\":\"" + reference.substring("Patient/".length()) + "\"}";
	}

	private static String observation(String subject) {
		return "{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":{\"text\":\"scopes\"},"
				+ "\"subject\":{\"reference\":\"" + subject + "\"}}";
	}

	/**
	 * Returns a transaction with one conditional update of an Observation of the patient. No Observation has the
	 * identifier value, so the conditional update has no match.
	 */
	private static String conditionalUpdate(String patient, String value) {
		return "{\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":[{\"resource\":"
				+ identifiedObservation(patient, value)
				+ ",\"request\":{\"method\":\"PUT\",\"url\":\"Observation?identifier=" + IDENTIFIER_SYSTEM + "|" + value
				+ "\"}}]}";
	}

	/** Returns a Bundle of the given type with one create of the resource, with the given URL and ifNoneExist. */
	private static String conditionalCreate(String bundleType, String url, String resource, String ifNoneExist) {
		return "{\"resourceType\":\"Bundle\",\"type\":\"" + bundleType + "\",\"entry\":[{\"resource\":" + resource
				+ ",\"request\":{\"method\":\"POST\",\"url\":\"" + url + "\",\"ifNoneExist\":\"" + ifNoneExist
				+ "\"}}]}";
	}

	/** Returns a batch with one GET entry for the URL. */
	private static String batchGet(String url) {
		return "{\"resourceType\":\"Bundle\",\"type\":\"batch\",\"entry\":[{\"request\":{\"method\":\"GET\",\"url\":\"" + url
				+ "\"}}]}";
	}

	/** Returns a Patient with one identifier of the system of this test. */
	private static String identifiedPatient(String value) {
		return "{\"resourceType\":\"Patient\",\"identifier\":[{\"system\":\"" + IDENTIFIER_SYSTEM + "\",\"value\":\""
				+ value + "\"}]}";
	}

	/** Returns an Observation of the patient with one identifier of the system of this test. */
	private static String identifiedObservation(String patient, String value) {
		return "{\"resourceType\":\"Observation\",\"identifier\":[{\"system\":\"" + IDENTIFIER_SYSTEM + "\",\"value\":\""
				+ value + "\"}],\"status\":\"final\",\"code\":{\"text\":\"scopes\"},"
				+ "\"subject\":{\"reference\":\"Patient/" + patient + "\"}}";
	}

	/** Returns the type and the id of each resource of a search Bundle, for example Patient/123. */
	private static Set<String> ids(HttpResponse<String> response) throws Exception {
		Set<String> ids = new HashSet<>();
		for (JsonNode entry : JSON.readTree(response.body()).path("entry")) {
			ids.add(entry.at("/resource/resourceType").asText() + "/" + entry.at("/resource/id").asText());
		}
		return ids;
	}

	/** Returns the status code of a Bundle entry, for example 403 of "403 Forbidden". */
	private static String entryStatus(JsonNode entry) {
		return entry.at("/response/status").asText().split(" ")[0];
	}

	private static String token(Object scope, String patient) throws JOSEException {
		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().subject("it-client");
		if (scope != null) {
			claims.claim("scope", scope);
		}
		if (patient != null) {
			claims.claim("patient", patient);
		}
		return ISSUER.sign(claims);
	}

	private HttpRequest.Builder request(String method, String path, String body) {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/fhir/" + path))
				.header("Accept", "application/fhir+json");
		if (body == null) {
			return request.method(method, BodyPublishers.noBody());
		}
		return request.header("Content-Type", "application/fhir+json").method(method, BodyPublishers.ofString(body));
	}

	private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
		return HTTP.send(request.build(), BodyHandlers.ofString());
	}
}
