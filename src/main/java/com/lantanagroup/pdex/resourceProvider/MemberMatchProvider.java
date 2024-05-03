package com.lantanagroup.pdex.resourceProvider;

/* -----------------------------------------------------------------------
// Corey Spears - This class is added to handle the $member-match operation.
// It utilizes and in some way replicates the MemberMatchR4ResourceProvider 
// from hapi-fhir-jpaserver-base-6.4.0.jar, ca.uhn.fhir.jpa.provider.r4.MemberMatchR4ResourceProvider;
// but makes some updates for the latest publication of HRex
----------------------------------------------------------------------- */

// Rick Geimer: this class needs to be registered in ca.uhn.fhir.jpa.starter.common.StarterJpaConfig in the restfulServer() method as follows:
// fhirServer.registerProvider(new MemberMatchProvider(fhirServer.getFhirContext(), daoRegistry));



import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.server.IResourceProvider;



import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.rest.server.provider.ProviderConstants;
import ca.uhn.fhir.model.api.annotation.Description;
import org.hl7.fhir.r4.model.Consent;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import java.util.Optional;

import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.jpa.provider.r4.MemberMatcherR4Helper;
import ca.uhn.fhir.jpa.provider.r4.IMemberMatchConsentHook;
import javax.annotation.Nullable;



/**
 * Class for processing the gfe-submit operation
 */
@Component
public class MemberMatchProvider implements IResourceProvider {
  private final Logger myLogger = LoggerFactory.getLogger(MemberMatchProvider.class.getName());

  private final FhirContext myFhirContext;
  private final MemberMatcherR4Helper myMemberMatcherR4Helper;

  @Autowired
  AppProperties appProperties;
	FhirContext theContext;
	IFhirResourceDao<Coverage> theCoverageDao;
	IFhirResourceDao<Patient> thePatientDao;
	IFhirResourceDao<Consent> theConsentDao;
	
  @Autowired(required = false) IMemberMatchConsentHook theExtensionProvider;


  @Override
  public Class<Patient> getResourceType() {
    return Patient.class;
  }

  /**
   * Constructor using a specific logger
   */
  public MemberMatchProvider(FhirContext ctx, DaoRegistry daoRegistry) {
    myFhirContext = ctx;
    theCoverageDao = daoRegistry.getResourceDao(Coverage.class);
    thePatientDao = daoRegistry.getResourceDao(Patient.class);
    theConsentDao = daoRegistry.getResourceDao(Consent.class);
    // MemberMatcherR4Helper seems to have theExtensionProvider as a no operation stub.
    //theExtensionProvider = 
    
    
    myMemberMatcherR4Helper = new MemberMatcherR4Helper(ctx, theCoverageDao, thePatientDao, theConsentDao, theExtensionProvider);
  }


  @Operation(name = ProviderConstants.OPERATION_MEMBER_MATCH, typeName = "Patient", canonicalUrl = "http://hl7.org/fhir/us/davinci-hrex/OperationDefinition/member-match", idempotent = false, returnParameters = {
		@OperationParam(name = "MemberIdentifier", typeName = "string")
	})
	public Parameters patientMemberMatch(
		HttpServletRequest theRequest,
    HttpServletResponse theResponse,

		@Description(shortDefinition = "The target of the operation. Will be returned with Identifier for matched coverage added.")
		@OperationParam(name = Constants.PARAM_MEMBER_PATIENT, min = 1, max = 1)
		Patient theMemberPatient,

		@Description(shortDefinition = "Old coverage information as extracted from beneficiary's card.")
		@OperationParam(name = "CoverageToMatch", min = 1, max = 1)
		Coverage oldCoverage,

		@Description(shortDefinition = "New Coverage information. Provided as a reference. Optionally returned unmodified.")
		@OperationParam(name = "CoverageToLink", min = 1, max = 1)
		Coverage newCoverage,

		@Description(shortDefinition = "Consent information. Consent held by the system seeking the match that grants permission to access the patient information.")
		@OperationParam(name = Constants.PARAM_CONSENT, min = 1, max = 1)
			Consent theConsent,

		RequestDetails theRequestDetails
	) {
    myLogger.info("Received MemberMatch Request");
    
    Parameters tempParams = doMemberMatchOperation(theMemberPatient, oldCoverage, newCoverage, theConsent, theRequestDetails);

    // outParams has some old parameter names that need to be replaced
    Parameters outParams = new Parameters();
    //IBaseParameters parameters = ParametersUtil.newInstance(myFhirContext);
    outParams.addParameter().setName(Constants.PARAM_MEMBER_IDENTIFIER).setValue(tempParams.getParameter(Constants.PARAM_MEMBER_IDENTIFIER).getValue());
    
  
    return outParams;
  
	}
  

  public Parameters doMemberMatchOperation(Patient theMemberPatient,
															Coverage theCoverageToMatch, Coverage theCoverageToLink, Consent theConsent, RequestDetails theRequestDetails) {

		validateParams(theMemberPatient, theCoverageToMatch, theCoverageToLink, theConsent);

		Optional<Coverage> coverageOpt = myMemberMatcherR4Helper.findMatchingCoverage(theCoverageToMatch, theRequestDetails);
		if (coverageOpt.isEmpty()) {
			String i18nMessage = myFhirContext.getLocalizer().getMessage(
				"operation.member.match.error.coverage.not.found");
			throw new UnprocessableEntityException(Msg.code(1155) + i18nMessage);
		}
		Coverage coverage = coverageOpt.get();

		Optional<Patient> patientOpt = myMemberMatcherR4Helper.getBeneficiaryPatient(coverage, theRequestDetails);
		if (patientOpt.isEmpty()) {
			String i18nMessage = myFhirContext.getLocalizer().getMessage(
				"operation.member.match.error.beneficiary.not.found");
			throw new UnprocessableEntityException(Msg.code(1156) + i18nMessage);
		}

		Patient patient = patientOpt.get();
		if (!myMemberMatcherR4Helper.validPatientMember(patient, theMemberPatient, theRequestDetails)) {
			String i18nMessage = myFhirContext.getLocalizer().getMessage(
				"operation.member.match.error.patient.not.found");
			throw new UnprocessableEntityException(Msg.code(2146) + i18nMessage);
		}

		if (patient.getIdentifier().isEmpty()) {
			String i18nMessage = myFhirContext.getLocalizer().getMessage(
				"operation.member.match.error.beneficiary.without.identifier");
			throw new UnprocessableEntityException(Msg.code(1157) + i18nMessage);
		}

		if (!myMemberMatcherR4Helper.validConsentDataAccess(theConsent)) {
			String i18nMessage = myFhirContext.getLocalizer().getMessage(
				"operation.member.match.error.consent.release.data.mismatch");
			throw new UnprocessableEntityException(Msg.code(2147) + i18nMessage);
		}

		myMemberMatcherR4Helper.addMemberIdentifierToMemberPatient(theMemberPatient, patient.getIdentifierFirstRep());
		myMemberMatcherR4Helper.updateConsentForMemberMatch(theConsent, patient, theMemberPatient, theRequestDetails);
		return myMemberMatcherR4Helper.buildSuccessReturnParameters(theMemberPatient, theCoverageToLink, theConsent);
	}

	private void validateParams(Patient theMemberPatient, Coverage theOldCoverage, Coverage theNewCoverage, Consent theConsent) {
		validateParam(theMemberPatient, Constants.PARAM_MEMBER_PATIENT);
		validateParam(theOldCoverage, Constants.PARAM_OLD_COVERAGE);
		validateParam(theNewCoverage, Constants.PARAM_NEW_COVERAGE);
		validateParam(theConsent, Constants.PARAM_CONSENT);
		validateMemberPatientParam(theMemberPatient);
		validateConsentParam(theConsent);
	}

	private void validateParam(@Nullable Object theParam, String theParamName) {
		if (theParam == null) {
			String i18nMessage = myFhirContext.getLocalizer().getMessage(
				"operation.member.match.error.missing.parameter", theParamName);
			throw new UnprocessableEntityException(Msg.code(1158) + i18nMessage);
		}
	}

	private void validateMemberPatientParam(Patient theMemberPatient) {
		if (theMemberPatient.getName().isEmpty()) {
			validateParam(null, Constants.PARAM_MEMBER_PATIENT_NAME);
		}

		validateParam(theMemberPatient.getName().get(0).getFamily(), Constants.PARAM_MEMBER_PATIENT_NAME);
		validateParam(theMemberPatient.getBirthDate(), Constants.PARAM_MEMBER_PATIENT_BIRTHDATE);
	}

	private void validateConsentParam(Consent theConsent) {
		if (theConsent.getPatient().isEmpty()) {
			validateParam(null, Constants.PARAM_CONSENT_PATIENT_REFERENCE);
		}
		if (theConsent.getPerformer().isEmpty()) {
			validateParam(null, Constants.PARAM_CONSENT_PERFORMER_REFERENCE);
		}
	}


}
