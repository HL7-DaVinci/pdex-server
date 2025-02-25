package com.lantanagroup.pdex.resourceProvider;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Consent;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Group;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Parameters.ParametersParameterComponent;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Group.GroupType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;

public class BulkMemberMatchProvider {

  private static final String MULTI_MEMBER_MATCH_OUT_PARAMETERS_PROFILE = "http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/pdex-parameters-multi-member-match-bundle-out";
  private static final String MULTI_MEMBER_MATCH_RESULT_CODESYSTEM = "http://hl7.org/fhir/us/davinci-pdex/CodeSystem/PdexMultiMemberMatchResultCS";
  private static final String MATCH_PARAMETERS_EXTENSION = "http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/base-ext-match-parameters";

  private final Logger myLogger = LoggerFactory.getLogger(BulkMemberMatchProvider.class.getName());

  FhirContext ctx;
  DaoRegistry daoRegistry;
  MemberMatchProvider memberMatchProvider;

  public BulkMemberMatchProvider(FhirContext ctx, DaoRegistry daoRegistry, MemberMatchProvider memberMatchProvider) {
    this.ctx = ctx;
    this.daoRegistry = daoRegistry;
    this.memberMatchProvider = memberMatchProvider;
  }

  @Operation(name = "$bulk-member-match", type = Group.class)
  public Parameters bulkMemberMatch(
    HttpServletRequest theRequest,
    HttpServletResponse theResponse,
    RequestDetails theRequestDetails,
    @OperationParam(name = "MemberBundle", min = 1, type = Parameters.class) List<ParametersParameterComponent> memberBundles
  ) {

    IFhirResourceDao<Group> groupDao = daoRegistry.getResourceDao(Group.class);

    Parameters retVal = new Parameters();
    retVal.setMeta(new Meta().addProfile(MULTI_MEMBER_MATCH_OUT_PARAMETERS_PROFILE));
    Group matchGroup = new Group().setType(GroupType.PERSON).setActual(true).setCode(
      new CodeableConcept().addCoding(
        new Coding(MULTI_MEMBER_MATCH_RESULT_CODESYSTEM, "match", "Matched")));
    Group noMatchGroup = new Group().setType(GroupType.PERSON).setActual(true).setCode(
      new CodeableConcept().addCoding(
        new Coding(MULTI_MEMBER_MATCH_RESULT_CODESYSTEM, "nomatch", "Not Matched")));
    Group consentGroup = new Group().setType(GroupType.PERSON).setActual(true).setCode(
      new CodeableConcept().addCoding(
        new Coding(MULTI_MEMBER_MATCH_RESULT_CODESYSTEM, "consentconstraint", "Consent Constraint")));

    retVal.addParameter().setName("ResourceIdentifier").setResource(matchGroup);
    retVal.addParameter().setName("NoMatch").setResource(noMatchGroup);
    retVal.addParameter().setName("ConsentConstraint").setResource(consentGroup);


    for (ParametersParameterComponent memberBundle : memberBundles) {

      Patient memberPatient = null;
      Coverage oldCoverage = null;
      Coverage newCoverage = null;
      Consent consent = null;

      for (ParametersParameterComponent memberBundleParameter : memberBundle.getPart()) {
        if (memberBundleParameter.getName().equals("MemberPatient")) {
          memberPatient = (Patient) memberBundleParameter.getResource();
        } else if (memberBundleParameter.getName().equals("CoverageToMatch")) {
          oldCoverage = (Coverage) memberBundleParameter.getResource();
        } else if (memberBundleParameter.getName().equals("CoverageToLink")) {
          newCoverage = (Coverage) memberBundleParameter.getResource();
        } else if (memberBundleParameter.getName().equals("Consent")) {
          consent = (Consent) memberBundleParameter.getResource();
        }
      }

      String refId = String.format("#%s", memberPatient.getIdPart());
      Extension extension = new Extension(MATCH_PARAMETERS_EXTENSION, new Reference(refId));
      
      try {
        memberMatchProvider.doMemberMatchOperation(memberPatient, oldCoverage, newCoverage, consent, theRequestDetails);

        Reference reference = new Reference().setReference(memberPatient.getId());
        reference.addExtension(extension);
        
        matchGroup.addContained(memberPatient);
        matchGroup.addMember().setEntity(reference);

      } catch (UnprocessableEntityException e) {

        // doMemberMatch throws an UnprocessableEntityException if match fails, consent failure is one specific case with a specific message,
        // the rest will be put in the "no match" group

        Reference reference = new Reference().setReference(refId);
        reference.addExtension(extension);

        if (e.getMessage().startsWith(Msg.code(2147))) {
          consentGroup.addContained(memberPatient);
          consentGroup.addMember().setEntity(reference);
        }
        else {
          noMatchGroup.addContained(memberPatient);
          noMatchGroup.addMember().setEntity(reference);
        }
      }
    }

    // Save the new matched Group
    groupDao.create(matchGroup, theRequestDetails);
    
    return retVal;
    
  }

  
}
