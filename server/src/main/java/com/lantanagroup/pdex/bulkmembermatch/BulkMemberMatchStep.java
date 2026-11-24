package com.lantanagroup.pdex.bulkmembermatch;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.hl7.fhir.r4.model.Binary;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Consent;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Group;
import org.hl7.fhir.r4.model.Group.GroupType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Parameters.ParametersParameterComponent;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.lantanagroup.pdex.resourceProvider.MemberMatchProvider;

import ca.uhn.fhir.batch2.api.IFirstJobStepWorker;
import ca.uhn.fhir.batch2.api.IJobDataSink;
import ca.uhn.fhir.batch2.api.JobExecutionFailedException;
import ca.uhn.fhir.batch2.api.RunOutcome;
import ca.uhn.fhir.batch2.api.StepExecutionDetails;
import ca.uhn.fhir.batch2.api.VoidModel;
import ca.uhn.fhir.batch2.jobs.export.models.BulkExportBinaryFileId;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;

import javax.annotation.Nonnull;

/**
 * Performs the member matching for a $bulk-member-match job, persists the matched
 * Group, and writes the resulting Group resources as an ndjson Binary referenced by
 * the async completion manifest.
 */
public class BulkMemberMatchStep implements IFirstJobStepWorker<BulkMemberMatchJobParameters, BulkExportBinaryFileId> {

  public static final String MULTI_MEMBER_MATCH_RESULT_CODESYSTEM = "http://hl7.org/fhir/us/davinci-pdex/CodeSystem/PdexMultiMemberMatchResultCS";
  public static final String MATCH_PARAMETERS_EXTENSION = "http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/base-ext-match-parameters";
  public static final String MATCH_COVERAGE_EXTENSION = "http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/base-ext-match-coverage";
  public static final String MULTI_MEMBER_MATCH_OUT_PROFILE = "http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/pdex-parameters-multi-member-match-bundle-out";
  private static final String IRCP_ACTOR_CODE = "IRCP";

  private static final Logger logger = LoggerFactory.getLogger(BulkMemberMatchStep.class);

  @Autowired
  private FhirContext fhirContext;

  @Autowired
  private DaoRegistry daoRegistry;

  @Autowired
  private MemberMatchProvider memberMatchProvider;

  @Nonnull
  @Override
  public RunOutcome run(
      @Nonnull StepExecutionDetails<BulkMemberMatchJobParameters, VoidModel> theStepExecutionDetails,
      @Nonnull IJobDataSink<BulkExportBinaryFileId> theDataSink) throws JobExecutionFailedException {

    BulkMemberMatchJobParameters jobParameters = theStepExecutionDetails.getParameters();
    boolean stu22 = BulkMemberMatchJobParameters.SPEC_STU2_2.equals(jobParameters.getSpecVersion());
    SystemRequestDetails requestDetails = new SystemRequestDetails();

    Parameters request = (Parameters) fhirContext.newJsonParser()
        .parseResource(jobParameters.getRequestParameters());

    Group matchedGroup = newResultGroup("match", "Matched", "Matched Members");
    Group noMatchGroup = newResultGroup("nomatch", "Not Matched", "Non Matched Members");
    Group consentConstrainedGroup = newResultGroup("consentconstraint", "Consent Constraint", "Consent Constrained Members");

    Identifier requestingPayer = null;
    Reference managingEntity = null;
    int memberIndex = 0;

    for (ParametersParameterComponent memberBundle : request.getParameter()) {
      if (!memberBundle.getName().equals("MemberBundle")) {
        continue;
      }
      memberIndex++;

      Patient memberPatient = null;
      Coverage coverageToMatch = null;
      Consent consent = null;
      for (ParametersParameterComponent part : memberBundle.getPart()) {
        switch (part.getName()) {
          case "MemberPatient" -> memberPatient = (Patient) part.getResource();
          case "CoverageToMatch" -> coverageToMatch = (Coverage) part.getResource();
          case "Consent" -> consent = (Consent) part.getResource();
        }
      }

      // pdex-150/151: contain pristine copies taken before matching mutates the inputs
      Patient containedPatient = scrubForContained(memberPatient.copy());
      if (containedPatient.getIdElement().getIdPart() == null) {
        containedPatient.setId("member-" + memberIndex);
      }
      // the contained CoverageToMatch and its extension only exist in STU 2.2 (pdex-153)
      Coverage containedCoverage = null;
      if (stu22) {
        containedCoverage = scrubForContained(coverageToMatch.copy());
        containedCoverage.setId("coverage-" + memberIndex);
      }

      if (requestingPayer == null) {
        requestingPayer = extractRequestingPayer(consent);
      }
      if (managingEntity == null && coverageToMatch.hasPayor()) {
        Reference payor = coverageToMatch.getPayorFirstRep();
        managingEntity = new Reference().setIdentifier(payor.getIdentifier()).setDisplay(payor.getDisplay());
      }

      try {
        Patient matchedPatient = memberMatchProvider.matchMember(memberPatient, coverageToMatch, consent, requestDetails);
        addMember(matchedGroup, containedPatient, containedCoverage,
            matchedPatient.getIdElement().toUnqualifiedVersionless().getValue());
      } catch (UnprocessableEntityException e) {
        // consent compliance failure has a specific message code; anything else is a failed match (pdex-154b)
        if (e.getMessage() != null && e.getMessage().startsWith(Msg.code(2147))) {
          addMember(consentConstrainedGroup, containedPatient, containedCoverage, null);
        } else {
          addMember(noMatchGroup, containedPatient, containedCoverage, null);
        }
      }
    }

    for (Group group : List.of(matchedGroup, noMatchGroup, consentConstrainedGroup)) {
      if (requestingPayer != null) {
        group.addIdentifier(requestingPayer);
      }
      group.setManagingEntity(managingEntity);
    }

    if (requestingPayer != null) {
      matchedGroup.addCharacteristic()
          .setCode(matchResultConcept("match", "Matched"))
          .setValue(new Reference().setIdentifier(requestingPayer))
          .setExclude(false)
          .setPeriod(new Period().setStart(jobParameters.getRequestDate()));
    }

    // MatchedMembers is always persisted and emitted, even when empty, so its id is
    // available for the subsequent $davinci-data-export
    IIdType matchedGroupId = daoRegistry.getResourceDao(Group.class)
        .create(matchedGroup, requestDetails).getId();
    matchedGroup.setId(matchedGroupId.toUnqualifiedVersionless());

    Binary binary = new Binary();
    if (stu22) {
      // STU 2.2: Group ndjson referenced from the bulk completion manifest
      StringBuilder ndjson = new StringBuilder();
      ndjson.append(fhirContext.newJsonParser().encodeResourceToString(matchedGroup)).append("\n");
      if (noMatchGroup.hasMember()) {
        ndjson.append(fhirContext.newJsonParser().encodeResourceToString(noMatchGroup)).append("\n");
      }
      if (consentConstrainedGroup.hasMember()) {
        ndjson.append(fhirContext.newJsonParser().encodeResourceToString(consentConstrainedGroup)).append("\n");
      }
      binary.setContentType(Constants.CT_FHIR_NDJSON);
      binary.setContent(ndjson.toString().getBytes(StandardCharsets.UTF_8));
    } else {
      // STU 2.1: Parameters envelope returned directly by the status endpoint
      Parameters envelope = new Parameters();
      envelope.getMeta().addProfile(MULTI_MEMBER_MATCH_OUT_PROFILE);
      envelope.addParameter().setName("MatchedMembers").setResource(matchedGroup);
      if (noMatchGroup.hasMember()) {
        envelope.addParameter().setName("NonMatchedMembers").setResource(noMatchGroup);
      }
      if (consentConstrainedGroup.hasMember()) {
        envelope.addParameter().setName("ConsentConstrainedMembers").setResource(consentConstrainedGroup);
      }
      binary.setContentType(Constants.CT_FHIR_JSON_NEW);
      binary.setContent(fhirContext.newJsonParser().encodeResourceToString(envelope)
          .getBytes(StandardCharsets.UTF_8));
    }
    IIdType binaryId = daoRegistry.getResourceDao(Binary.class).create(binary, requestDetails).getId();

    logger.info("Bulk member match ({} mode) processed {} member(s); matched group: {}",
        jobParameters.getSpecVersion(), memberIndex, matchedGroupId.toUnqualifiedVersionless().getValue());

    BulkExportBinaryFileId fileId = new BulkExportBinaryFileId();
    fileId.setResourceType(stu22 ? "Group" : "Parameters");
    fileId.setBinaryId(binaryId.toUnqualifiedVersionless().getValue());
    theDataSink.accept(fileId);

    return RunOutcome.SUCCESS;
  }

  private Group newResultGroup(String code, String display, String name) {
    return new Group()
        .setType(GroupType.PERSON)
        .setActual(true)
        .setCode(matchResultConcept(code, display))
        .setName(name);
  }

  private CodeableConcept matchResultConcept(String code, String display) {
    return new CodeableConcept().addCoding(new Coding(MULTI_MEMBER_MATCH_RESULT_CODESYSTEM, code, display));
  }

  /**
   * pdex-151a: strip elements prohibited on contained resources so the copy stays
   * base-FHIR-conformant while preserving id, identifiers, and demographics.
   */
  private <T extends DomainResource> T scrubForContained(T resource) {
    resource.getMeta().setVersionId(null);
    resource.getMeta().setLastUpdated(null);
    resource.getMeta().getSecurity().clear();
    resource.getContained().clear();
    return resource;
  }

  private void addMember(Group group, Patient containedPatient, Coverage containedCoverage, String matchedPatientReference) {
    String patientId = containedPatient.getIdElement().getIdPart();

    // pdex-153: the same patient submitted with different coverages is contained once
    boolean alreadyContained = group.getContained().stream()
        .anyMatch(c -> patientId.equals(c.getIdElement().getIdPart()));
    if (!alreadyContained) {
      group.addContained(containedPatient);
    }

    Reference entity = new Reference(
        matchedPatientReference != null ? matchedPatientReference : "#" + patientId);
    entity.addExtension(new Extension(MATCH_PARAMETERS_EXTENSION, new Reference("#" + patientId)));
    if (containedCoverage != null) {
      group.addContained(containedCoverage);
      entity.addExtension(new Extension(MATCH_COVERAGE_EXTENSION,
          new Reference("#" + containedCoverage.getIdElement().getIdPart())));
    }
    group.addMember().setEntity(entity);
  }

  private Identifier extractRequestingPayer(Consent consent) {
    return consent.getProvision().getActor().stream()
        .filter(actor -> actor.getRole().getCoding().stream().anyMatch(c -> IRCP_ACTOR_CODE.equals(c.getCode())))
        .map(actor -> actor.getReference().getIdentifier())
        .filter(identifier -> identifier != null && !identifier.isEmpty())
        .findFirst()
        .orElse(null);
  }

}
