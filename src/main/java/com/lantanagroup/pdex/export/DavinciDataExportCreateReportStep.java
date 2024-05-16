package com.lantanagroup.pdex.export;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ca.uhn.fhir.batch2.api.ChunkExecutionDetails;
import ca.uhn.fhir.batch2.api.IJobDataSink;
import ca.uhn.fhir.batch2.api.IJobInstance;
import ca.uhn.fhir.batch2.api.IReductionStepWorker;
import ca.uhn.fhir.batch2.api.JobExecutionFailedException;
import ca.uhn.fhir.batch2.api.RunOutcome;
import ca.uhn.fhir.batch2.api.StepExecutionDetails;
import ca.uhn.fhir.batch2.jobs.export.models.BulkExportBinaryFileId;
import ca.uhn.fhir.batch2.model.ChunkOutcome;
import ca.uhn.fhir.batch2.model.JobInstance;
import ca.uhn.fhir.jpa.api.model.BulkExportJobResults;

public class DavinciDataExportCreateReportStep
    implements IReductionStepWorker<DavinciDataExportJobParameters, BulkExportBinaryFileId, BulkExportJobResults> {

  private static final Logger logger = LoggerFactory.getLogger(DavinciDataExportCreateReportStep.class);

  // @Autowired
  // private BulkExportCreateReportStep bulkExportCreateReportStep;

  private Map<String, List<String>> myResourceToBinaryIds;

  @Nonnull
  @Override
  public RunOutcome run(
      @Nonnull StepExecutionDetails<DavinciDataExportJobParameters, BulkExportBinaryFileId> theStepExecutionDetails,
      @Nonnull IJobDataSink<BulkExportJobResults> theDataSink)
      throws JobExecutionFailedException {
    BulkExportJobResults results = new BulkExportJobResults();

    String requestUrl = getOriginatingRequestUrl(theStepExecutionDetails, results);
    results.setOriginalRequestUrl(requestUrl);

    if (myResourceToBinaryIds != null) {
      logger.info(
          "Bulk Export Report creation step for instance: {}",
          theStepExecutionDetails.getInstance().getInstanceId());

      results.setResourceTypeToBinaryIds(myResourceToBinaryIds);

      myResourceToBinaryIds = null;
    } else {
      String msg = "Export complete, but no data to generate report for job instance: "
          + theStepExecutionDetails.getInstance().getInstanceId();
      logger.warn(msg);

      results.setReportMsg(msg);
    }

    // accept saves the report
    theDataSink.accept(results);
    return RunOutcome.SUCCESS;
  }

  @Nonnull
	@Override
	public ChunkOutcome consume(
			ChunkExecutionDetails<DavinciDataExportJobParameters, BulkExportBinaryFileId> theChunkDetails) {
		BulkExportBinaryFileId fileId = theChunkDetails.getData();
		if (myResourceToBinaryIds == null) {
			myResourceToBinaryIds = new HashMap<>();
		}

		myResourceToBinaryIds.putIfAbsent(fileId.getResourceType(), new ArrayList<>());

		myResourceToBinaryIds.get(fileId.getResourceType()).add(fileId.getBinaryId());

		return ChunkOutcome.SUCCESS();
	}

  private static String getOriginatingRequestUrl(
      @Nonnull StepExecutionDetails<DavinciDataExportJobParameters, BulkExportBinaryFileId> theStepExecutionDetails,
      BulkExportJobResults results) {
    IJobInstance instance = theStepExecutionDetails.getInstance();
    String url = "";
    if (instance instanceof JobInstance) {
      JobInstance jobInstance = (JobInstance) instance;
      DavinciDataExportJobParameters parameters = jobInstance.getParameters(DavinciDataExportJobParameters.class);
      String originalRequestUrl = parameters.getOriginalRequestUrl();
      url = originalRequestUrl;
    }
    return url;
  }

}
