package com.lantanagroup.pdex.resourceProvider;
/* -----------------------------------------------------------------------
// This class is added to handle the $member-match operation.
// $member-match operation was utilizing MemberMatchR4ResourceProvider from hapi-fhir-jpaserver-base-6.4.0.jar
// In Recent HAPI updates the MemberMatchR4ResourceProvider and related classes are removed.
// Using old code temporarily until new/custom implementation that is conformant with the HRex definition of $member-match is implemented
----------------------------------------------------------------------- */
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.DateParam;
import ca.uhn.fhir.rest.param.StringOrListParam;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.param.TokenOrListParam;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.util.ParametersUtil;
import com.google.common.collect.Lists;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseParameters;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Consent;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Identifier.IdentifierUse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MemberMatcherHelper {
    static final Logger ourLog = LoggerFactory.getLogger(MemberMatcherHelper.class);
    private static final String OUT_COVERAGE_IDENTIFIER_CODE_SYSTEM = "http://terminology.hl7.org/CodeSystem/v2-0203";
    private static final String OUT_COVERAGE_IDENTIFIER_CODE = "MB";
    private static final String OUT_COVERAGE_IDENTIFIER_TEXT = "Member Number";
    private static final String COVERAGE_TYPE = "Coverage";
    private static final String CONSENT_POLICY_REGULAR_TYPE = "regular";
    private static final String CONSENT_POLICY_SENSITIVE_TYPE = "sensitive";
    public static final String CONSENT_IDENTIFIER_CODE_SYSTEM = "https://smilecdr.com/fhir/ns/member-match-source-client";
    private final FhirContext myFhirContext;
    private final IFhirResourceDao<Coverage> myCoverageDao;
    private final IFhirResourceDao<Patient> myPatientDao;
    private final IFhirResourceDao<Consent> myConsentDao;
    private final Consumer<IBaseResource> myConsentModifier;
    private boolean myRegularFilterSupported = false;

    public MemberMatcherHelper(FhirContext theContext, IFhirResourceDao<Coverage> theCoverageDao, IFhirResourceDao<Patient> thePatientDao, IFhirResourceDao<Consent> theConsentDao, Consumer<IBaseResource> theConsentModifier) {
        this.myFhirContext = theContext;
        this.myConsentDao = theConsentDao;
        this.myPatientDao = thePatientDao;
        this.myCoverageDao = theCoverageDao;
        this.myConsentModifier = (theConsentModifier != null ? theConsentModifier : (noop) -> {});
    }

    public Optional<Coverage> findMatchingCoverage(Coverage theCoverageToMatch, RequestDetails theRequestDetails) {
        List<IBaseResource> foundCoverages = this.findCoverageByCoverageId(theCoverageToMatch, theRequestDetails);
        if (foundCoverages.size() == 1 && this.isCoverage((IBaseResource)foundCoverages.get(0))) {
            return Optional.of((Coverage)foundCoverages.get(0));
        } else {
            foundCoverages = this.findCoverageByCoverageIdentifier(theCoverageToMatch, theRequestDetails);
            return foundCoverages.size() == 1 && this.isCoverage((IBaseResource)foundCoverages.get(0)) ? Optional.of((Coverage)foundCoverages.get(0)) : Optional.empty();
        }
    }

    private List<IBaseResource> findCoverageByCoverageIdentifier(Coverage theCoverageToMatch, RequestDetails theRequestDetails) {
        TokenOrListParam identifierParam = new TokenOrListParam();

        for(Identifier identifier : theCoverageToMatch.getIdentifier()) {
            identifierParam.add(identifier.getSystem(), identifier.getValue());
        }

        SearchParameterMap paramMap = (new SearchParameterMap()).add("identifier", identifierParam);
        IBundleProvider retVal = this.myCoverageDao.search(paramMap, theRequestDetails);
        return retVal.getAllResources();
    }

    private boolean isCoverage(IBaseResource theIBaseResource) {
        return theIBaseResource.fhirType().equals(COVERAGE_TYPE);
    }

    private List<IBaseResource> findCoverageByCoverageId(Coverage theCoverageToMatch, RequestDetails theRequestDetails) {
        SearchParameterMap paramMap = (new SearchParameterMap()).add("_id", new StringParam(theCoverageToMatch.getId()));
        IBundleProvider retVal = this.myCoverageDao.search(paramMap, theRequestDetails);
        return retVal.getAllResources();
    }

    public void updateConsentForMemberMatch(Consent theConsent, Patient thePatient, Patient theMemberPatient, RequestDetails theRequestDetails) {
        this.addIdentifierToConsent(theConsent, theMemberPatient);
        this.updateConsentPatientAndPerformer(theConsent, thePatient);
        this.myConsentModifier.accept(theConsent);
        this.myConsentDao.create(theConsent, theRequestDetails);
    }

    public Parameters buildSuccessReturnParameters(Patient theMemberPatient, Coverage theCoverage, Consent theConsent) {
        IBaseParameters parameters = ParametersUtil.newInstance(this.myFhirContext);
        ParametersUtil.addParameterToParameters(this.myFhirContext, parameters, "MemberPatient", theMemberPatient);
        ParametersUtil.addParameterToParameters(this.myFhirContext, parameters, "NewCoverage", theCoverage);
        ParametersUtil.addParameterToParameters(this.myFhirContext, parameters, "Consent", theConsent);
        ParametersUtil.addParameterToParameters(this.myFhirContext, parameters, "MemberIdentifier", this.getIdentifier(theMemberPatient));
        return (Parameters)parameters;
    }

    private Identifier getIdentifier(Patient theMemberPatient) {
        return (Identifier)theMemberPatient.getIdentifier().stream().filter(this::isTypeMB).findFirst().orElseThrow(() -> {
            String i18nMessage = this.myFhirContext.getLocalizer().getMessage("operation.member.match.error.beneficiary.without.identifier", new Object[0]);
            String var10002 = Msg.code(2219);
            return new UnprocessableEntityException(var10002 + i18nMessage);
        });
    }

    private boolean isTypeMB(Identifier theMemberIdentifier) {
        return theMemberIdentifier.getType() != null && theMemberIdentifier.getType().getCoding().stream().anyMatch((typeCoding) -> typeCoding.getCode().equals(OUT_COVERAGE_IDENTIFIER_CODE));
    }

    public void addMemberIdentifierToMemberPatient(Patient theMemberPatient, Identifier theNewIdentifier) {
        Coding coding = (new Coding()).setSystem("http://terminology.hl7.org/CodeSystem/v2-0203").setCode(OUT_COVERAGE_IDENTIFIER_CODE).setDisplay(OUT_COVERAGE_IDENTIFIER_TEXT).setUserSelected(false);
        CodeableConcept concept = (new CodeableConcept()).setCoding(Lists.newArrayList(new Coding[]{coding})).setText(OUT_COVERAGE_IDENTIFIER_TEXT);
        Identifier newIdentifier = (new Identifier()).setUse(IdentifierUse.USUAL).setType(concept).setSystem(theNewIdentifier.getSystem()).setValue(theNewIdentifier.getValue());
        theMemberPatient.addIdentifier(newIdentifier);
    }

    public Optional<Patient> getBeneficiaryPatient(Coverage theCoverage, RequestDetails theRequestDetails) {
        if (theCoverage.getBeneficiaryTarget() == null && theCoverage.getBeneficiary() == null) {
            return Optional.empty();
        } else if (theCoverage.getBeneficiaryTarget() != null && !theCoverage.getBeneficiaryTarget().getIdentifier().isEmpty()) {
            return Optional.of(theCoverage.getBeneficiaryTarget());
        } else {
            Reference beneficiaryRef = theCoverage.getBeneficiary();
            if (beneficiaryRef == null) {
                return Optional.empty();
            } else if (beneficiaryRef.getResource() != null) {
                return Optional.of((Patient)beneficiaryRef.getResource());
            } else if (beneficiaryRef.getReference() == null) {
                return Optional.empty();
            } else {
                Patient beneficiary = (Patient)this.myPatientDao.read(new IdDt(beneficiaryRef.getReference()), theRequestDetails);
                return Optional.ofNullable(beneficiary);
            }
        }
    }

    public boolean validPatientMember(Patient thePatientFromContract, Patient thePatientToMatch, RequestDetails theRequestDetails) {
        if (thePatientFromContract != null && thePatientFromContract.getIdElement() != null && thePatientToMatch != null) {
            StringOrListParam familyName = new StringOrListParam();

            for(HumanName name : thePatientToMatch.getName()) {
                familyName.addOr(new StringParam(name.getFamily()));
            }

            SearchParameterMap map = (new SearchParameterMap()).add("family", familyName).add("birthdate", new DateParam(thePatientToMatch.getBirthDateElement().getValueAsString()));
            IBundleProvider bundle = this.myPatientDao.search(map, theRequestDetails);

            for(IBaseResource patientResource : bundle.getAllResources()) {
                IIdType patientId = patientResource.getIdElement().toUnqualifiedVersionless();
                if (patientId.getValue().equals(thePatientFromContract.getIdElement().toUnqualifiedVersionless().getValue())) {
                    return true;
                }
            }

            return false;
        } else {
            return false;
        }
    }

    public boolean validConsentDataAccess(Consent theConsent) {
        if (theConsent.getPolicy().isEmpty()) {
            return false;
        } else {
            for(Consent.ConsentPolicyComponent policyComponent : theConsent.getPolicy()) {
                if (policyComponent.getUri() == null || !this.validConsentPolicy(policyComponent.getUri())) {
                    return false;
                }
            }

            return true;
        }
    }

    private boolean validConsentPolicy(String thePolicyUri) {
        String policyTypes = StringUtils.substringAfterLast(thePolicyUri, "#");
        if (policyTypes.equals(CONSENT_POLICY_SENSITIVE_TYPE)) {
            return true;
        } else {
            return policyTypes.equals(CONSENT_POLICY_REGULAR_TYPE) && this.myRegularFilterSupported;
        }
    }

    private void addIdentifierToConsent(Consent theConsent, Patient thePatient) {
        String consentId = this.getIdentifier(thePatient).getValue();
        Identifier consentIdentifier = (new Identifier()).setSystem(CONSENT_IDENTIFIER_CODE_SYSTEM).setValue(consentId);
        theConsent.addIdentifier(consentIdentifier);
    }

    public void setRegularFilterSupported(boolean theRegularFilterSupported) {
        this.myRegularFilterSupported = theRegularFilterSupported;
    }

    private void updateConsentPatientAndPerformer(Consent theConsent, Patient thePatient) {
        String patientRef = thePatient.getIdElement().toUnqualifiedVersionless().getValue();
        theConsent.getPatient().setReference(patientRef);
        theConsent.getPerformer().set(0, new Reference(patientRef));
    }
}
