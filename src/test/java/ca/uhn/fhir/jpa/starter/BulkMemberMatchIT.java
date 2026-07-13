package ca.uhn.fhir.jpa.starter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.hl7.fhir.r4.model.Binary;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Consent;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Group;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Parameters.ParametersParameterComponent;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ca.uhn.fhir.batch2.api.IJobMaintenanceService;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = {Application.class}, properties = {
    "spring.datasource.url=jdbc:h2:mem:dbr4-bulk-member-match",
    "hapi.fhir.fhir_version=r4",
    "hapi.fhir.cr_enabled=false",
    "security.enable-auth=false",
    "security.enable-proxy=false"
})
class BulkMemberMatchIT {

  private static final String RESULT_CS = "http://hl7.org/fhir/us/davinci-pdex/CodeSystem/PdexMultiMemberMatchResultCS";
  private static final String MATCH_PARAMETERS_EXT = "http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/base-ext-match-parameters";
  private static final String MATCH_COVERAGE_EXT = "http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/base-ext-match-coverage";
  private static final String MB_SYSTEM = "http://terminology.hl7.org/CodeSystem/v2-0203";
  private static final String NPI_SYSTEM = "http://hl7.org/fhir/sid/us-npi";
  private static final String REQUESTING_PAYER_NPI = "0123456789";
  private static final String OLD_PAYER_NPI = "9876543210";
  private static final String SENSITIVE_POLICY = "http://hl7.org/fhir/us/davinci-hrex/StructureDefinition-hrex-consent.html#sensitive";
  private static final String REGULAR_POLICY = "http://hl7.org/fhir/us/davinci-hrex/StructureDefinition-hrex-consent.html#regular";

  @LocalServerPort
  private int port;

  @Autowired
  private IJobMaintenanceService jobMaintenanceService;

  private FhirContext ctx;
  private IGenericClient client;
  private String serverBase;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    ctx = FhirContext.forR4();
    ctx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
    ctx.getRestfulClientFactory().setSocketTimeout(1200 * 1000);
    serverBase = "http://localhost:" + port + "/fhir";
    client = ctx.newRestfulGenericClient(serverBase);
  }

  @Test
  void testBulkMemberMatchAsyncFlow() throws Exception {
    String patientAId = seedPatient("PersonA", "1974-12-25", "55678");
    String patientBId = seedPatient("PersonB", "1980-06-15", "55679");
    seedCoverage("coverage-system-a", "COV-A", patientAId);
    seedCoverage("coverage-system-b", "COV-B", patientBId);

    Parameters request = new Parameters();
    // matched, with optional CoverageToLink
    request.addParameter(memberBundle("1", "PersonA", "1974-12-25", "55678",
        "coverage-system-a", "COV-A", SENSITIVE_POLICY, true));
    // matched, without CoverageToLink (it is optional)
    request.addParameter(memberBundle("2", "PersonB", "1980-06-15", "55679",
        "coverage-system-b", "COV-B", SENSITIVE_POLICY, false));
    // no match: unknown coverage
    request.addParameter(memberBundle("3", "PersonC", "1990-01-01", "99999",
        "coverage-system-x", "COV-X", SENSITIVE_POLICY, false));
    // matched but consent cannot be complied with (regular policy unsupported)
    request.addParameter(memberBundle("4", "PersonA", "1974-12-25", "55678",
        "coverage-system-a", "COV-A", REGULAR_POLICY, false));

    String pollUrl = kickoff(request);
    assertTrue(pollUrl.contains("$bulk-member-match-poll-status"));

    // default STU 2.1: completed status returns the Parameters envelope directly
    Parameters envelope = (Parameters) ctx.newJsonParser().parseResource(pollUntilComplete(pollUrl));

    Group matched = (Group) envelope.getParameter("MatchedMembers").getResource();
    Group noMatch = (Group) envelope.getParameter("NonMatchedMembers").getResource();
    Group consentConstrained = (Group) envelope.getParameter("ConsentConstrainedMembers").getResource();
    assertEquals("match", matched.getCode().getCodingFirstRep().getCode());
    assertEquals(RESULT_CS, matched.getCode().getCodingFirstRep().getSystem());
    assertEquals("nomatch", noMatch.getCode().getCodingFirstRep().getCode());
    assertEquals("consentconstraint", consentConstrained.getCode().getCodingFirstRep().getCode());

    // matched group is persisted and its members reference this server's patients
    assertNotNull(matched.getIdElement().getIdPart());
    Group storedGroup = client.read().resource(Group.class).withId(matched.getIdElement().getIdPart()).execute();
    assertEquals(2, storedGroup.getMember().size());

    assertEquals(2, matched.getMember().size());
    List<String> matchedRefs = matched.getMember().stream()
        .map(m -> m.getEntity().getReference()).toList();
    assertTrue(matchedRefs.contains("Patient/" + patientAId));
    assertTrue(matchedRefs.contains("Patient/" + patientBId));

    // submitted patients are contained, unmodified ids, referenced via extension;
    // the match-coverage extension is STU 2.2 only
    for (Group.GroupMemberComponent member : matched.getMember()) {
      Reference entity = member.getEntity();
      assertEquals(null, entity.getExtensionByUrl(MATCH_COVERAGE_EXT));
      String extRef = ((Reference) entity.getExtensionByUrl(MATCH_PARAMETERS_EXT).getValue()).getReference();
      assertTrue(extRef.startsWith("#"));
      String containedId = extRef.substring(1);
      // HAPI's parser retains the '#' prefix on contained resource ids
      assertTrue(matched.getContained().stream()
          .anyMatch(c -> c instanceof Patient
              && containedId.equals(c.getIdElement().getIdPart().replaceFirst("^#", ""))));
    }

    // requesting payer identifier and characteristic per PDexMemberMatchGroup
    assertEquals(REQUESTING_PAYER_NPI, matched.getIdentifierFirstRep().getValue());
    assertFalse(matched.getCharacteristic().isEmpty());
    assertEquals("match", matched.getCharacteristicFirstRep().getCode().getCodingFirstRep().getCode());
    assertEquals(REQUESTING_PAYER_NPI,
        ((Reference) matched.getCharacteristicFirstRep().getValue()).getIdentifier().getValue());
    assertEquals(OLD_PAYER_NPI, matched.getManagingEntity().getIdentifier().getValue());

    assertEquals(1, noMatch.getMember().size());
    assertEquals("#3", noMatch.getMemberFirstRep().getEntity().getReference());

    assertEquals(1, consentConstrained.getMember().size());
    assertEquals("#4", consentConstrained.getMemberFirstRep().getEntity().getReference());
  }

  @Test
  void testEmptyMatchedGroupIsStillEmitted_Stu22ViaPreferToken() throws Exception {
    Parameters request = new Parameters();
    request.addParameter(memberBundle("1", "Nobody", "2000-01-01", "00000",
        "coverage-system-none", "COV-NONE", SENSITIVE_POLICY, false));

    String pollUrl = kickoffWithHeader(request, "Prefer", "respond-async, pdex-spec=2.2");
    JsonNode manifest = objectMapper.readTree(pollUntilComplete(pollUrl));
    assertEquals("Group", manifest.get("output").get(0).get("type").asText());
    List<Group> groups = fetchGroupNdjson(manifest.get("output").get(0).get("url").asText());

    Group matched = groupByCode(groups, "match");
    assertTrue(matched.getMember().isEmpty());
    // id must exist so it can be used with $davinci-data-export
    assertNotNull(matched.getIdElement().getIdPart());
    assertEquals(1, groupByCode(groups, "nomatch").getMember().size());
  }

  @Test
  void testStu22ViaHeaderReturnsManifestWithCoverageExtension() throws Exception {
    String patientDId = seedPatient("PersonD", "1965-03-03", "55680");
    seedCoverage("coverage-system-d", "COV-D", patientDId);

    Parameters request = new Parameters();
    request.addParameter(memberBundle("1", "PersonD", "1965-03-03", "55680",
        "coverage-system-d", "COV-D", SENSITIVE_POLICY, false));

    String pollUrl = kickoffWithHeader(request, "X-PDex-Spec-Version", "2.2");
    JsonNode manifest = objectMapper.readTree(pollUntilComplete(pollUrl));

    assertEquals(false, manifest.get("requiresAccessToken").asBoolean());
    assertEquals("Group", manifest.get("output").get(0).get("type").asText());

    List<Group> groups = fetchGroupNdjson(manifest.get("output").get(0).get("url").asText());
    Group matched = groupByCode(groups, "match");
    assertEquals(1, matched.getMember().size());
    Reference entity = matched.getMemberFirstRep().getEntity();
    assertEquals("Patient/" + patientDId, entity.getReference());
    assertNotNull(entity.getExtensionByUrl(MATCH_COVERAGE_EXT));
    assertTrue(matched.getContained().stream().anyMatch(c -> c instanceof Coverage));
  }

  @Test
  void testUnsupportedSpecVersionRejected() throws Exception {
    Parameters request = new Parameters();
    request.addParameter(memberBundle("1", "PersonA", "1974-12-25", "55678",
        "coverage-system-a", "COV-A", SENSITIVE_POLICY, false));

    try (CloseableHttpClient http = HttpClients.createDefault()) {
      HttpPost post = kickoffPost(request, true);
      post.setHeader("X-PDex-Spec-Version", "3.0");
      try (CloseableHttpResponse response = http.execute(post)) {
        assertEquals(400, response.getStatusLine().getStatusCode());
      }
    }
  }

  @Test
  void testKickoffRequiresPreferAsyncHeader() throws Exception {
    Parameters request = new Parameters();
    request.addParameter(memberBundle("1", "PersonA", "1974-12-25", "55678",
        "coverage-system-a", "COV-A", SENSITIVE_POLICY, false));

    try (CloseableHttpClient http = HttpClients.createDefault()) {
      HttpPost post = kickoffPost(request, false);
      try (CloseableHttpResponse response = http.execute(post)) {
        assertEquals(400, response.getStatusLine().getStatusCode());
      }
    }
  }

  @Test
  void testBadBundleFormatReturns422() throws Exception {
    // missing required Consent part
    ParametersParameterComponent bundle = memberBundle("1", "PersonA", "1974-12-25", "55678",
        "coverage-system-a", "COV-A", SENSITIVE_POLICY, false);
    bundle.getPart().removeIf(p -> p.getName().equals("Consent"));
    Parameters request = new Parameters();
    request.addParameter(bundle);

    try (CloseableHttpClient http = HttpClients.createDefault()) {
      try (CloseableHttpResponse response = http.execute(kickoffPost(request, true))) {
        assertEquals(422, response.getStatusLine().getStatusCode());
        String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
        assertTrue(body.contains("OperationOutcome"));
        assertTrue(body.contains("Consent"));
      }
    }

    // no MemberBundle at all
    try (CloseableHttpClient http = HttpClients.createDefault()) {
      try (CloseableHttpResponse response = http.execute(kickoffPost(new Parameters(), true))) {
        assertEquals(422, response.getStatusLine().getStatusCode());
      }
    }
  }

  private String kickoff(Parameters request) throws Exception {
    return executeKickoff(kickoffPost(request, true));
  }

  private String kickoffWithHeader(Parameters request, String headerName, String headerValue) throws Exception {
    HttpPost post = kickoffPost(request, true);
    post.setHeader(headerName, headerValue);
    return executeKickoff(post);
  }

  private String executeKickoff(HttpPost post) throws Exception {
    try (CloseableHttpClient http = HttpClients.createDefault()) {
      try (CloseableHttpResponse response = http.execute(post)) {
        assertEquals(202, response.getStatusLine().getStatusCode());
        assertNotNull(response.getFirstHeader("Content-Location"));
        return response.getFirstHeader("Content-Location").getValue();
      }
    }
  }

  private HttpPost kickoffPost(Parameters request, boolean preferAsync) {
    HttpPost post = new HttpPost(serverBase + "/Group/$bulk-member-match");
    post.setEntity(new StringEntity(ctx.newJsonParser().encodeResourceToString(request),
        ContentType.create("application/fhir+json", StandardCharsets.UTF_8)));
    if (preferAsync) {
      post.setHeader("Prefer", "respond-async");
    }
    return post;
  }

  private String pollUntilComplete(String pollUrl) throws Exception {
    await().atMost(3, TimeUnit.MINUTES).pollInterval(2, TimeUnit.SECONDS).until(() -> {
      try {
        jobMaintenanceService.runMaintenancePass();
      } catch (Exception e) {
        // maintenance pass may already be running; polling continues regardless
      }
      return pollStatusCode(pollUrl) == 200;
    });

    try (CloseableHttpClient http = HttpClients.createDefault()) {
      try (CloseableHttpResponse response = http.execute(new HttpGet(pollUrl))) {
        return EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
      }
    }
  }

  private int pollStatusCode(String pollUrl) throws Exception {
    try (CloseableHttpClient http = HttpClients.createDefault()) {
      try (CloseableHttpResponse response = http.execute(new HttpGet(pollUrl))) {
        int status = response.getStatusLine().getStatusCode();
        if (status >= 400) {
          throw new IllegalStateException("Bulk member match job failed: "
              + EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8));
        }
        return status;
      }
    }
  }

  private List<Group> fetchGroupNdjson(String url) {
    Binary binary = client.read().resource(Binary.class)
        .withId(new IdType(url.substring(url.indexOf("/Binary/") + 1)).getIdPart()).execute();
    String ndjson = new String(binary.getContent(), StandardCharsets.UTF_8);

    List<Group> groups = new ArrayList<>();
    for (String line : ndjson.split("\n")) {
      if (!line.isBlank()) {
        groups.add((Group) ctx.newJsonParser().parseResource(line));
      }
    }
    return groups;
  }

  private Group groupByCode(List<Group> groups, String code) {
    Group group = groups.stream()
        .filter(g -> code.equals(g.getCode().getCodingFirstRep().getCode())
            && RESULT_CS.equals(g.getCode().getCodingFirstRep().getSystem()))
        .findFirst().orElse(null);
    assertNotNull(group, "Expected a Group with result code " + code);
    return group;
  }

  private String seedPatient(String family, String birthDate, String memberId) {
    Patient patient = new Patient();
    patient.addName().setFamily(family).addGiven("Test");
    patient.getBirthDateElement().setValueAsString(birthDate);
    patient.addIdentifier(mbIdentifier(memberId));
    return client.create().resource(patient).execute().getId().getIdPart();
  }

  private void seedCoverage(String identifierSystem, String identifierValue, String patientId) {
    Coverage coverage = new Coverage();
    coverage.setStatus(Coverage.CoverageStatus.ACTIVE);
    coverage.addIdentifier().setSystem(identifierSystem).setValue(identifierValue);
    coverage.setBeneficiary(new Reference("Patient/" + patientId));
    coverage.addPayor(new Reference().setIdentifier(
        new Identifier().setSystem(NPI_SYSTEM).setValue(OLD_PAYER_NPI)).setDisplay("Old Health Plan"));
    client.create().resource(coverage).execute();
  }

  private Identifier mbIdentifier(String value) {
    return new Identifier()
        .setType(new CodeableConcept().addCoding(new Coding(MB_SYSTEM, "MB", "Member Number")))
        .setSystem("http://example.org/old-payer/identifiers/member")
        .setValue(value);
  }

  private ParametersParameterComponent memberBundle(String patientId, String family, String birthDate,
      String memberId, String coverageIdentifierSystem, String coverageIdentifierValue,
      String consentPolicy, boolean includeCoverageToLink) {

    Patient memberPatient = new Patient();
    memberPatient.setId(patientId);
    memberPatient.addName().setFamily(family).addGiven("Test");
    memberPatient.getBirthDateElement().setValueAsString(birthDate);
    memberPatient.addIdentifier(mbIdentifier(memberId));

    Coverage coverageToMatch = new Coverage();
    coverageToMatch.setId("coverage-to-match-" + patientId);
    coverageToMatch.setStatus(Coverage.CoverageStatus.DRAFT);
    coverageToMatch.addIdentifier().setSystem(coverageIdentifierSystem).setValue(coverageIdentifierValue);
    coverageToMatch.setBeneficiary(new Reference("Patient/" + patientId));
    coverageToMatch.addPayor(new Reference().setIdentifier(
        new Identifier().setSystem(NPI_SYSTEM).setValue(OLD_PAYER_NPI)).setDisplay("Old Health Plan"));

    Consent consent = new Consent();
    consent.setStatus(Consent.ConsentState.ACTIVE);
    consent.setScope(new CodeableConcept().addCoding(
        new Coding("http://terminology.hl7.org/CodeSystem/consentscope", "patient-privacy", null)));
    consent.addCategory(new CodeableConcept().addCoding(
        new Coding("http://terminology.hl7.org/CodeSystem/v3-ActCode", "IDSCL", null)));
    consent.setPatient(new Reference("Patient/" + patientId));
    consent.addPerformer(new Reference("Patient/" + patientId));
    consent.addPolicy().setUri(consentPolicy);
    Consent.ProvisionComponent provision = consent.getProvision();
    provision.setType(Consent.ConsentProvisionType.PERMIT);
    provision.addActor()
        .setRole(new CodeableConcept().addCoding(
            new Coding("http://terminology.hl7.org/CodeSystem/provenance-participant-type", "performer", null)))
        .setReference(new Reference().setIdentifier(
            new Identifier().setSystem(NPI_SYSTEM).setValue(OLD_PAYER_NPI)).setDisplay("Old Health Plan"));
    provision.addActor()
        .setRole(new CodeableConcept().addCoding(
            new Coding("http://terminology.hl7.org/CodeSystem/v3-ParticipationType", "IRCP", null)))
        .setReference(new Reference().setIdentifier(
            new Identifier().setSystem(NPI_SYSTEM).setValue(REQUESTING_PAYER_NPI)).setDisplay("New Health Plan"));

    ParametersParameterComponent bundle = new ParametersParameterComponent();
    bundle.setName("MemberBundle");
    bundle.addPart().setName("MemberPatient").setResource(memberPatient);
    bundle.addPart().setName("CoverageToMatch").setResource(coverageToMatch);
    bundle.addPart().setName("Consent").setResource(consent);
    if (includeCoverageToLink) {
      Coverage coverageToLink = new Coverage();
      coverageToLink.setId("coverage-to-link-" + patientId);
      coverageToLink.setStatus(Coverage.CoverageStatus.DRAFT);
      coverageToLink.setBeneficiary(new Reference("Patient/" + patientId));
      bundle.addPart().setName("CoverageToLink").setResource(coverageToLink);
    }
    return bundle;
  }

}
