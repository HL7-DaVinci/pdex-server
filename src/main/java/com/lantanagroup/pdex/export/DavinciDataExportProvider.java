package com.lantanagroup.pdex.export;

import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseOperationOutcome;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.Group;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.InstantType;
import org.hl7.fhir.r4.model.Parameters;
import org.joda.time.DateTimeZone;
import org.joda.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ca.uhn.fhir.batch2.api.IJobCoordinator;
import ca.uhn.fhir.batch2.api.JobOperationResultJson;
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
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.server.bulk.BulkExportJobParameters.ExportStyle;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import ca.uhn.fhir.util.ArrayUtil;
import ca.uhn.fhir.util.DatatypeUtil;
import ca.uhn.fhir.util.JsonUtil;
import ca.uhn.fhir.util.OperationOutcomeUtil;
import ca.uhn.fhir.util.UrlUtil;

public class DavinciDataExportProvider {

  public static final String OPERATION_DAVINCI_DATA_EXPORT = "$davinci-data-export";
  public static final String OPERATION_DAVINCI_DATA_EXPORT_POLL_STATUS = "$davinci-export-poll-status";
  public static final String PAYER_TO_PAYER_EXPORT_TYPE = "hl7.fhir.us.davinci-pdex#payertopayer";
  public static final String PROVIDER_DELTA_EXPORT_TYPE = "hl7.fhir.us.davinci-pdex#provider-delta";
  public static final String PROVIDER_DOWNLOAD_EXPORT_TYPE = "hl7.fhir.us.davinci-pdex#provider-download";
  public static final String PROVIDER_SNAPSHOT_EXPORT_TYPE = "hl7.fhir.us.davinci-pdex#provider-snapshot";

  public static final Set<String> DEFAULT_RESOURCE_TYPES = Set.of("Group", "Patient", "Coverage");

  private final Logger logger = LoggerFactory.getLogger(DavinciDataExportProvider.class);

  private FhirContext ctx;
  private DaoRegistry daoRegistry;
  private IJobCoordinator jobCoordinator;

  public DavinciDataExportProvider(FhirContext ctx, DaoRegistry daoRegistry, IJobCoordinator jobCoordinator) {
    this.ctx = ctx;
    this.daoRegistry = daoRegistry;
    this.jobCoordinator = jobCoordinator;
  }

  @Operation(name = OPERATION_DAVINCI_DATA_EXPORT, type = Group.class, idempotent = true, manualResponse = true)
  public void davinciDataExport(
      @IdParam IIdType theId,
      @OperationParam(name = "_outputFormat", min = 0, max = 1, typeName = "string") IPrimitiveType<String> theOutputFormat,
      @OperationParam(name = "_type", min = 0, max = 1, typeName = "string") IPrimitiveType<String> theType,
      @OperationParam(name = "_since", min = 0, max = 1, typeName = "instant") IPrimitiveType<Date> theSince,
      @OperationParam(name = "_typeFilter", min = 0, max = OperationParam.MAX_UNLIMITED, typeName = "string") List<IPrimitiveType<String>> theTypeFilter,
      @OperationParam(name = "patient", min = 0, max = OperationParam.MAX_UNLIMITED, typeName = "string") IPrimitiveType<String> thePatient,
      @OperationParam(name = "exportType", min = 0, max = 1, typeName = "canonical") CanonicalType theExportType,
      @OperationParam(name = "_until", min = 0, max = 1, typeName = "instant") IPrimitiveType<Date> theUntil,
      ServletRequestDetails theRequestDetails) {

    // require "Prefer: respond-async" request header
    BulkDataExportProvider.validatePreferAsyncHeader(theRequestDetails, OPERATION_DAVINCI_DATA_EXPORT);

    // ensure Group resource exists... throws ResourceNotFoundException if not
    daoRegistry.getResourceDao(Group.class).read(theId, theRequestDetails);

    // prepare parameters for job
    DavinciDataExportJobParameters jobParameters = buildJobParameters(theId, theOutputFormat, theType, theSince,
        theTypeFilter, thePatient, theExportType, theUntil);

    jobParameters.setOriginalRequestUrl(theRequestDetails.getCompleteUrl());

    // start the job
    startJob(theRequestDetails, jobParameters);

  }

  private void startJob(ServletRequestDetails theRequestDetails, DavinciDataExportJobParameters theJobParameters) {

    JobInstanceStartRequest startRequest = new JobInstanceStartRequest();
    startRequest.setParameters(theJobParameters);
    startRequest.setJobDefinitionId(DavinciDataExportCtx.DAVINCI_DATA_EXPORT);

    Batch2JobStartResponse response = jobCoordinator.startInstance(theRequestDetails, startRequest);

    writePollingLocationToResponseHeaders(theRequestDetails, response.getInstanceId());

  }

  private DavinciDataExportJobParameters buildJobParameters(
      IIdType theGroupId,
      IPrimitiveType<String> theOutputFormat,
      IPrimitiveType<String> theType,
      IPrimitiveType<Date> theSince,
      List<IPrimitiveType<String>> theTypeFilter,
      IPrimitiveType<String> thePatient,
      CanonicalType theExportType,
      IPrimitiveType<Date> theUntil) {

    DavinciDataExportJobParameters params = new DavinciDataExportJobParameters();
    params.setOriginalTypeFilters(splitTypeFilters(theTypeFilter));

    String outputFormat = theOutputFormat != null ? theOutputFormat.getValueAsString() : Constants.CT_FHIR_NDJSON;

    Set<String> resourceTypes = null;
    if (theType != null) {
      resourceTypes = ArrayUtil.commaSeparatedListToCleanSet(theType.getValueAsString());
    } else {
      resourceTypes = DEFAULT_RESOURCE_TYPES;
    }

    // _since parameter may need changed or just set to a default value based on the
    // export type
    Date fiveYearsAgo = LocalDate.now(DateTimeZone.UTC).minusYears(5).toDate();
    Date januaryFirstStart = LocalDate.parse("2016-01-01").toDate();
    Date since = null;
    if (theSince != null) {
      since = theSince.getValue();

      // payer to payer requires overriding the start date if it is more than five
      // years ago
      if (theExportType.equals(PAYER_TO_PAYER_EXPORT_TYPE) && since.before(fiveYearsAgo)) {
        since = fiveYearsAgo;
      }

      // provider export can go back as far as January 1, 2016
      else if (theExportType.equals(PROVIDER_DELTA_EXPORT_TYPE) || theExportType.equals(PROVIDER_DOWNLOAD_EXPORT_TYPE)
          || theExportType.equals(PROVIDER_SNAPSHOT_EXPORT_TYPE) && since.before(januaryFirstStart)) {
        since = januaryFirstStart;
      }

    }

    // no supplied _since means we need a default start date depending on the export
    // type
    else {

      // payer to payer defaults to five years of data
      if (theExportType.equals(PAYER_TO_PAYER_EXPORT_TYPE)) {
        since = fiveYearsAgo;
      }

      // provider export defaults to January 1, 2016
      else if (theExportType.equals(PROVIDER_DELTA_EXPORT_TYPE) || theExportType.equals(PROVIDER_DOWNLOAD_EXPORT_TYPE)
          || theExportType.equals(PROVIDER_SNAPSHOT_EXPORT_TYPE)) {
        since = januaryFirstStart;
      }

    }

    Set<String> typeFilters = splitTypeFilters(theTypeFilter);

    // _until parameter having a value means we need to override the regular HAPI
    // implementation of bulk export which only allows the _since lower bound
    Date until = null;
    if (theUntil != null) {
      until = theUntil.getValue();

      // HAPI bulk export implementation cannot take an upper bound for the last
      // modified date range and always
      // passes in null as the upper bound of the date range when "since" is present.
      // We will set type filters for the date range for each resource type being
      // exported and set "since" back to null so these aren't overriden down the
      // line.

      for (var resource : resourceTypes) {
        if (typeFilters == null) {
          typeFilters = new HashSet<>();
        }
        String filter = resource + "?";

        // since is set so do full date range search
        if (since != null) {
          filter += "_lastUpdated=ge" + since.toInstant().toString() + "&_lastUpdated=le"
              + until.toInstant().toString();
        }
        // only until is set
        else {
          filter += "_lastUpdated=le" + until.toInstant().toString();
        }

        typeFilters.add(filter);
      }

      since = null;
    }

    logger.info("typeFilters: " + typeFilters);

    params.setFilters(typeFilters);
    params.setSince(since);
    params.setUntil(until);
    params.setResourceTypes(resourceTypes);
    params.setOutputFormat(outputFormat);
    params.setExportStyle(ExportStyle.GROUP);
    params.setGroupId(DatatypeUtil.toStringValue(theGroupId));
    return params;
  }

  // https://github.com/hapifhir/hapi-fhir/blob/125513a10009041289b0d0570c30aa4fd637ab0a/hapi-fhir-storage-batch2-jobs/src/main/java/ca/uhn/fhir/batch2/jobs/export/BulkDataExportProvider.java#L769

  public void writePollingLocationToResponseHeaders(ServletRequestDetails theRequestDetails, String theInstanceId) {
    String serverBase = StringUtils.removeEnd(theRequestDetails.getServerBaseForRequest(), "/");
    if (serverBase == null) {
      throw new InternalErrorException(Msg.code(2136) + "Unable to get the server base.");
    }
    String pollLocation = serverBase + "/" + OPERATION_DAVINCI_DATA_EXPORT_POLL_STATUS + "?"
        + JpaConstants.PARAM_EXPORT_POLL_STATUS_JOB_ID + "=" + theInstanceId;
    pollLocation = UrlUtil.sanitizeHeaderValue(pollLocation);

    HttpServletResponse response = theRequestDetails.getServletResponse();

    // Add standard headers
    theRequestDetails.getServer().addHeadersToResponse(response);

    // Successful 202 Accepted
    response.addHeader(Constants.HEADER_CONTENT_LOCATION, pollLocation);
    response.setStatus(Constants.STATUS_HTTP_202_ACCEPTED);
  }

  private Set<String> splitTypeFilters(List<IPrimitiveType<String>> theTypeFilter) {
    if (theTypeFilter == null) {
      return null;
    }

    Set<String> retVal = new HashSet<>();

    for (IPrimitiveType<String> next : theTypeFilter) {
      String typeFilterString = next.getValueAsString();
      Arrays.stream(typeFilterString.split(BulkDataExportProvider.FARM_TO_TABLE_TYPE_FILTER_REGEX))
          .filter(StringUtils::isNotBlank)
          .forEach(retVal::add);
    }

    return retVal;
  }

  /*
   * Status poll operation. The default $export-poll-status operation attempts to
   * deserialize the job parameters as BulkExportJobParameters
   */
  @SuppressWarnings("unchecked")
  @Operation(name = OPERATION_DAVINCI_DATA_EXPORT_POLL_STATUS, manualResponse = true, idempotent = true, deleteEnabled = true)
  public void exportPollStatus(
      @OperationParam(name = JpaConstants.PARAM_EXPORT_POLL_STATUS_JOB_ID, typeName = "string", min = 0, max = 1) IPrimitiveType<String> theJobId,
      ServletRequestDetails theRequestDetails)
      throws IOException {
    HttpServletResponse response = theRequestDetails.getServletResponse();
    theRequestDetails.getServer().addHeadersToResponse(response);

    // When export-poll-status through POST
    // Get theJobId from the request details
    if (theJobId == null) {
      Parameters parameters = (Parameters) theRequestDetails.getResource();
      Parameters.ParametersParameterComponent parameter = parameters.getParameter().stream()
          .filter(param -> param.getName().equals(JpaConstants.PARAM_EXPORT_POLL_STATUS_JOB_ID))
          .findFirst()
          .orElseThrow(() -> new InvalidRequestException(Msg.code(2227)
              + "$export-poll-status requires a job ID, please provide the value of target jobId."));
      theJobId = (IPrimitiveType<String>) parameter.getValue();
    }

    JobInstance info = jobCoordinator.getInstance(theJobId.getValueAsString());

    switch (info.getStatus()) {
      case COMPLETED:
        if (theRequestDetails.getRequestType() == RequestTypeEnum.DELETE) {
          handleDeleteRequest(theJobId, response, info.getStatus());
        } else {
          response.setStatus(Constants.STATUS_HTTP_200_OK);
          response.setContentType(Constants.CT_JSON);

          // Create a JSON response
          BulkExportResponseJson bulkResponseDocument = new BulkExportResponseJson();
          bulkResponseDocument.setTransactionTime(info.getEndTime()); // completed

          bulkResponseDocument.setRequiresAccessToken(true);

          String report = info.getReport();
          if (StringUtils.isEmpty(report)) {
            // this should never happen, but just in case...
            logger.error("No report for completed bulk export job.");
            response.getWriter().close();
          } else {
            BulkExportJobResults results = JsonUtil.deserialize(report, BulkExportJobResults.class);
            bulkResponseDocument.setMsg(results.getReportMsg());
            bulkResponseDocument.setRequest(results.getOriginalRequestUrl());

            String serverBase = StringUtils.removeEnd(theRequestDetails.getServerBaseForRequest(), "/");

            for (Map.Entry<String, List<String>> entrySet : results.getResourceTypeToBinaryIds().entrySet()) {
              String resourceType = entrySet.getKey();
              List<String> binaryIds = entrySet.getValue();
              for (String binaryId : binaryIds) {
                IIdType iId = new IdType(binaryId);
                String nextUrl = serverBase + "/"
                    + iId.toUnqualifiedVersionless().getValue();
                bulkResponseDocument
                    .addOutput()
                    .setType(resourceType)
                    .setUrl(nextUrl);
              }
            }
            JsonUtil.serialize(bulkResponseDocument, response.getWriter());
            response.getWriter().close();
          }
        }
        break;
      case FAILED:
        response.setStatus(Constants.STATUS_HTTP_500_INTERNAL_ERROR);
        response.setContentType(Constants.CT_FHIR_JSON);

        // Create an OperationOutcome response
        IBaseOperationOutcome oo = OperationOutcomeUtil.newInstance(ctx);

        OperationOutcomeUtil.addIssue(ctx, oo, "error", info.getErrorMessage(), null, null);
        ctx.newJsonParser().setPrettyPrint(true).encodeResourceToWriter(oo, response.getWriter());
        response.getWriter().close();
        break;
      default:
        // Deliberate fall through
        logger.warn(
            "Unrecognized status encountered: {}. Treating as BUILDING/SUBMITTED",
            info.getStatus().name());
        // noinspection fallthrough
      case FINALIZE:
      case QUEUED:
      case IN_PROGRESS:
      case CANCELLED:
      case ERRORED:
        if (theRequestDetails.getRequestType() == RequestTypeEnum.DELETE) {
          handleDeleteRequest(theJobId, response, info.getStatus());
        } else {
          response.setStatus(Constants.STATUS_HTTP_202_ACCEPTED);
          String dateString = getTransitionTimeOfJobInfo(info);
          response.addHeader(
              Constants.HEADER_X_PROGRESS,
              "Build in progress - Status set to " + info.getStatus() + " at " + dateString);
          response.addHeader(Constants.HEADER_RETRY_AFTER, "120");
        }
        break;
    }
  }

  private void handleDeleteRequest(IPrimitiveType<String> theJobId, HttpServletResponse response,
      StatusEnum theOrigStatus) throws IOException {
    IBaseOperationOutcome outcome = OperationOutcomeUtil.newInstance(ctx);
    JobOperationResultJson resultMessage = jobCoordinator.cancelInstance(theJobId.getValueAsString());
    if (theOrigStatus.equals(StatusEnum.COMPLETED)) {
      response.setStatus(Constants.STATUS_HTTP_404_NOT_FOUND);
      OperationOutcomeUtil.addIssue(
          ctx,
          outcome,
          "error",
          "Job instance <" + theJobId.getValueAsString()
              + "> was already cancelled or has completed.  Nothing to do.",
          null,
          null);
    } else {
      response.setStatus(Constants.STATUS_HTTP_202_ACCEPTED);
      OperationOutcomeUtil.addIssue(
          ctx, outcome, "information", resultMessage.getMessage(), null, "informational");
    }
    ctx.newJsonParser().setPrettyPrint(true).encodeResourceToWriter(outcome, response.getWriter());
    response.getWriter().close();
  }

  private String getTransitionTimeOfJobInfo(JobInstance theInfo) {
    if (theInfo.getEndTime() != null) {
      return new InstantType(theInfo.getEndTime()).getValueAsString();
    } else if (theInfo.getStartTime() != null) {
      return new InstantType(theInfo.getStartTime()).getValueAsString();
    } else {
      // safety check
      return "";
    }
  }

}
