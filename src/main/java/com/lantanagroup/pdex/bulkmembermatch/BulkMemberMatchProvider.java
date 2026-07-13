package com.lantanagroup.pdex.bulkmembermatch;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseOperationOutcome;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.hl7.fhir.r4.model.Binary;
import org.hl7.fhir.r4.model.Consent;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Group;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.InstantType;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Parameters.ParametersParameterComponent;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.fhir.batch2.api.IJobCoordinator;
import ca.uhn.fhir.batch2.jobs.export.BulkDataExportProvider;
import ca.uhn.fhir.batch2.model.JobInstance;
import ca.uhn.fhir.batch2.model.JobInstanceStartRequest;
import ca.uhn.fhir.batch2.model.StatusEnum;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.BulkExportJobResults;
import ca.uhn.fhir.jpa.batch.models.Batch2JobStartResponse;
import ca.uhn.fhir.jpa.bulk.export.model.BulkExportResponseJson;
import ca.uhn.fhir.jpa.model.util.JpaConstants;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import ca.uhn.fhir.util.JsonUtil;
import ca.uhn.fhir.util.OperationOutcomeUtil;
import ca.uhn.fhir.util.UrlUtil;

import jakarta.servlet.http.HttpServletResponse;

/**
 * PDex $bulk-member-match: async kickoff returning 202 + Content-Location, with the
 * result retrieved from $bulk-member-match-poll-status. Responses conform to STU 2.1
 * (Parameters envelope) by default; a client selects STU 2.2 behavior (bulk manifest +
 * Group ndjson) per request via the X-PDex-Spec-Version header or a pdex-spec Prefer token.
 */
public class BulkMemberMatchProvider {

  public static final String OPERATION_BULK_MEMBER_MATCH = "$bulk-member-match";
  public static final String OPERATION_BULK_MEMBER_MATCH_POLL_STATUS = "$bulk-member-match-poll-status";
  public static final String SPEC_VERSION_HEADER = "X-PDex-Spec-Version";
  private static final Pattern PREFER_SPEC_PATTERN = Pattern.compile("pdex-spec=([\\w.]+)");

  private final Logger myLogger = LoggerFactory.getLogger(BulkMemberMatchProvider.class.getName());

  FhirContext ctx;
  DaoRegistry daoRegistry;
  IJobCoordinator jobCoordinator;

  public BulkMemberMatchProvider(FhirContext ctx, DaoRegistry daoRegistry, IJobCoordinator jobCoordinator) {
    this.ctx = ctx;
    this.daoRegistry = daoRegistry;
    this.jobCoordinator = jobCoordinator;
  }

  @Operation(name = OPERATION_BULK_MEMBER_MATCH, type = Group.class, manualResponse = true)
  public void bulkMemberMatch(
      @OperationParam(name = "MemberBundle", min = 0, max = OperationParam.MAX_UNLIMITED) List<ParametersParameterComponent> memberBundles,
      ServletRequestDetails theRequestDetails) {

    BulkDataExportProvider.validatePreferAsyncHeader(theRequestDetails, OPERATION_BULK_MEMBER_MATCH);
    String specVersion = resolveSpecVersion(theRequestDetails);
    validateMemberBundles(memberBundles);

    Parameters request = new Parameters();
    memberBundles.forEach(request::addParameter);

    BulkMemberMatchJobParameters jobParameters = new BulkMemberMatchJobParameters();
    jobParameters.setRequestParameters(ctx.newJsonParser().encodeResourceToString(request));
    jobParameters.setOriginalRequestUrl(theRequestDetails.getCompleteUrl());
    jobParameters.setRequestDate(new Date());
    jobParameters.setSpecVersion(specVersion);

    JobInstanceStartRequest startRequest = new JobInstanceStartRequest();
    startRequest.setJobDefinitionId(BulkMemberMatchCtx.BULK_MEMBER_MATCH);
    startRequest.setParameters(jobParameters);

    Batch2JobStartResponse response = jobCoordinator.startInstance(theRequestDetails, startRequest);

    myLogger.info("Started bulk member match job {} for {} member bundle(s)",
        response.getInstanceId(), memberBundles.size());

    writePollingLocationToResponseHeaders(theRequestDetails, response.getInstanceId());
  }

  /**
   * Reads the requested spec version from the X-PDex-Spec-Version header or a
   * pdex-spec token in the Prefer header; absent means STU 2.1.
   */
  private String resolveSpecVersion(ServletRequestDetails theRequestDetails) {
    String requested = theRequestDetails.getHeader(SPEC_VERSION_HEADER);
    if (requested == null) {
      String prefer = theRequestDetails.getHeader(Constants.HEADER_PREFER);
      if (prefer != null) {
        Matcher matcher = PREFER_SPEC_PATTERN.matcher(prefer);
        if (matcher.find()) {
          requested = matcher.group(1);
        }
      }
    }

    if (requested == null || requested.startsWith(BulkMemberMatchJobParameters.SPEC_STU2_1)) {
      return BulkMemberMatchJobParameters.SPEC_STU2_1;
    }
    if (requested.startsWith(BulkMemberMatchJobParameters.SPEC_STU2_2)) {
      return BulkMemberMatchJobParameters.SPEC_STU2_2;
    }
    throw new InvalidRequestException("Unsupported PDex spec version '" + requested
        + "'; supported versions are 2.1 (default) and 2.2");
  }

  /** Malformed input is rejected synchronously with 422 + OperationOutcome (pdex-155). */
  private void validateMemberBundles(List<ParametersParameterComponent> memberBundles) {
    if (memberBundles == null || memberBundles.isEmpty()) {
      throw new UnprocessableEntityException(
          "Bad bundle format: at least one MemberBundle parameter is required");
    }

    int index = 0;
    for (ParametersParameterComponent memberBundle : memberBundles) {
      index++;
      requirePart(memberBundle, "MemberPatient", Patient.class, index, true);
      requirePart(memberBundle, "CoverageToMatch", Coverage.class, index, true);
      requirePart(memberBundle, "Consent", Consent.class, index, true);
      requirePart(memberBundle, "CoverageToLink", Coverage.class, index, false);
    }
  }

  private void requirePart(ParametersParameterComponent memberBundle, String partName,
      Class<? extends Resource> resourceType, int index, boolean required) {

    ParametersParameterComponent part = memberBundle.getPart().stream()
        .filter(p -> partName.equals(p.getName()))
        .findFirst()
        .orElse(null);

    if (part == null) {
      if (required) {
        throw new UnprocessableEntityException(String.format(
            "Bad bundle format: MemberBundle[%d] is missing required part '%s'", index, partName));
      }
      return;
    }

    if (!resourceType.isInstance(part.getResource())) {
      throw new UnprocessableEntityException(String.format(
          "Bad bundle format: MemberBundle[%d] part '%s' must contain a %s resource",
          index, partName, resourceType.getSimpleName()));
    }
  }

  @Operation(name = OPERATION_BULK_MEMBER_MATCH_POLL_STATUS, manualResponse = true, idempotent = true, deleteEnabled = true)
  public void bulkMemberMatchPollStatus(
      @OperationParam(name = JpaConstants.PARAM_EXPORT_POLL_STATUS_JOB_ID, typeName = "string", min = 1, max = 1) IPrimitiveType<String> theJobId,
      ServletRequestDetails theRequestDetails) throws IOException {

    HttpServletResponse response = theRequestDetails.getServletResponse();
    theRequestDetails.getServer().addHeadersToResponse(response);

    JobInstance info = jobCoordinator.getInstance(theJobId.getValueAsString());
    if (!BulkMemberMatchCtx.BULK_MEMBER_MATCH.equals(info.getJobDefinitionId())) {
      throw new ResourceNotFoundException(
          "Job instance <" + theJobId.getValueAsString() + "> is not a bulk member match job");
    }

    if (theRequestDetails.getRequestType() == RequestTypeEnum.DELETE) {
      handleDeleteRequest(theJobId, response, info);
      return;
    }

    switch (info.getStatus()) {
      case COMPLETED:
        writeCompletedResponse(theRequestDetails, response, info);
        break;

      case FAILED:
        response.setStatus(Constants.STATUS_HTTP_500_INTERNAL_ERROR);
        response.setContentType(Constants.CT_FHIR_JSON);
        IBaseOperationOutcome oo = OperationOutcomeUtil.newInstance(ctx);
        OperationOutcomeUtil.addIssue(ctx, oo, "error", info.getErrorMessage(), null, null);
        ctx.newJsonParser().setPrettyPrint(true).encodeResourceToWriter(oo, response.getWriter());
        response.getWriter().close();
        break;

      default:
        response.setStatus(Constants.STATUS_HTTP_202_ACCEPTED);
        response.addHeader(Constants.HEADER_X_PROGRESS,
            "Bulk member match in progress - Status set to " + info.getStatus() + " at "
                + getTransitionTime(info));
        response.addHeader(Constants.HEADER_RETRY_AFTER, "120");
        break;
    }
  }

  /**
   * STU 2.1 jobs report a Parameters Binary whose content is returned directly as the
   * operation response; STU 2.2 jobs report Group ndjson Binaries referenced from a
   * Bulk Data completion manifest.
   */
  private void writeCompletedResponse(ServletRequestDetails theRequestDetails, HttpServletResponse response,
      JobInstance info) throws IOException {

    String report = info.getReport();
    if (StringUtils.isEmpty(report)) {
      myLogger.error("No report for completed bulk member match job {}", info.getInstanceId());
      response.setStatus(Constants.STATUS_HTTP_500_INTERNAL_ERROR);
      response.getWriter().close();
      return;
    }
    BulkExportJobResults results = JsonUtil.deserialize(report, BulkExportJobResults.class);
    Map<String, List<String>> binaryIds = results.getResourceTypeToBinaryIds();

    response.setStatus(Constants.STATUS_HTTP_200_OK);

    if (binaryIds.containsKey("Parameters")) {
      Binary envelope = daoRegistry.getResourceDao(Binary.class)
          .read(new IdType(binaryIds.get("Parameters").get(0)), theRequestDetails);
      response.setContentType(Constants.CT_FHIR_JSON_NEW);
      response.getOutputStream().write(envelope.getContent());
      response.getOutputStream().close();
      return;
    }

    BulkExportResponseJson manifest = new BulkExportResponseJson();
    manifest.setTransactionTime(info.getEndTime());
    // public unauthenticated server, output Binaries are openly readable
    manifest.setRequiresAccessToken(false);
    manifest.setMsg(results.getReportMsg());
    manifest.setRequest(results.getOriginalRequestUrl());

    String serverBase = StringUtils.removeEnd(theRequestDetails.getServerBaseForRequest(), "/");
    for (Map.Entry<String, List<String>> entrySet : binaryIds.entrySet()) {
      for (String binaryId : entrySet.getValue()) {
        String url = serverBase + "/" + new IdType(binaryId).toUnqualifiedVersionless().getValue();
        manifest.addOutput().setType(entrySet.getKey()).setUrl(url);
      }
    }
    response.setContentType(Constants.CT_JSON);
    JsonUtil.serialize(manifest, response.getWriter());
    response.getWriter().close();
  }

  private void handleDeleteRequest(IPrimitiveType<String> theJobId, HttpServletResponse response, JobInstance info)
      throws IOException {
    IBaseOperationOutcome outcome = OperationOutcomeUtil.newInstance(ctx);
    var resultMessage = jobCoordinator.cancelInstance(theJobId.getValueAsString());
    if (info.getStatus() == StatusEnum.COMPLETED) {
      response.setStatus(Constants.STATUS_HTTP_404_NOT_FOUND);
      OperationOutcomeUtil.addIssue(ctx, outcome, "error",
          "Job instance <" + theJobId.getValueAsString() + "> was already cancelled or has completed. Nothing to do.",
          null, null);
    } else {
      response.setStatus(Constants.STATUS_HTTP_202_ACCEPTED);
      OperationOutcomeUtil.addIssue(ctx, outcome, "information", resultMessage.getMessage(), null, "informational");
    }
    ctx.newJsonParser().setPrettyPrint(true).encodeResourceToWriter(outcome, response.getWriter());
    response.getWriter().close();
  }

  private String getTransitionTime(JobInstance info) {
    if (info.getEndTime() != null) {
      return new InstantType(info.getEndTime()).getValueAsString();
    } else if (info.getStartTime() != null) {
      return new InstantType(info.getStartTime()).getValueAsString();
    }
    return "";
  }

  private void writePollingLocationToResponseHeaders(ServletRequestDetails theRequestDetails, String theInstanceId) {
    String serverBase = StringUtils.removeEnd(theRequestDetails.getServerBaseForRequest(), "/");
    if (serverBase == null) {
      throw new InternalErrorException(Msg.code(2136) + "Unable to get the server base.");
    }
    String pollLocation = serverBase + "/" + OPERATION_BULK_MEMBER_MATCH_POLL_STATUS + "?"
        + JpaConstants.PARAM_EXPORT_POLL_STATUS_JOB_ID + "=" + theInstanceId;
    pollLocation = UrlUtil.sanitizeHeaderValue(pollLocation);

    HttpServletResponse response = theRequestDetails.getServletResponse();
    theRequestDetails.getServer().addHeadersToResponse(response);
    response.addHeader(Constants.HEADER_CONTENT_LOCATION, pollLocation);
    response.setStatus(Constants.STATUS_HTTP_202_ACCEPTED);
  }

}
