package com.lantanagroup.pdex.bulkmembermatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import ca.uhn.fhir.batch2.api.ChunkExecutionDetails;
import ca.uhn.fhir.batch2.api.IJobDataSink;
import ca.uhn.fhir.batch2.api.IReductionStepWorker;
import ca.uhn.fhir.batch2.api.JobExecutionFailedException;
import ca.uhn.fhir.batch2.api.RunOutcome;
import ca.uhn.fhir.batch2.api.StepExecutionDetails;
import ca.uhn.fhir.batch2.jobs.export.models.BulkExportBinaryFileId;
import ca.uhn.fhir.batch2.model.ChunkOutcome;
import ca.uhn.fhir.jpa.api.model.BulkExportJobResults;

public class BulkMemberMatchReportStep
    implements IReductionStepWorker<BulkMemberMatchJobParameters, BulkExportBinaryFileId, BulkExportJobResults> {

  private Map<String, List<String>> resourceTypeToBinaryIds;

  @Nonnull
  @Override
  public ChunkOutcome consume(
      ChunkExecutionDetails<BulkMemberMatchJobParameters, BulkExportBinaryFileId> theChunkDetails) {
    BulkExportBinaryFileId fileId = theChunkDetails.getData();
    if (resourceTypeToBinaryIds == null) {
      resourceTypeToBinaryIds = new HashMap<>();
    }
    resourceTypeToBinaryIds.computeIfAbsent(fileId.getResourceType(), k -> new ArrayList<>())
        .add(fileId.getBinaryId());
    return ChunkOutcome.SUCCESS();
  }

  @Nonnull
  @Override
  public RunOutcome run(
      @Nonnull StepExecutionDetails<BulkMemberMatchJobParameters, BulkExportBinaryFileId> theStepExecutionDetails,
      @Nonnull IJobDataSink<BulkExportJobResults> theDataSink)
      throws JobExecutionFailedException {

    BulkExportJobResults results = new BulkExportJobResults();
    results.setOriginalRequestUrl(theStepExecutionDetails.getParameters().getOriginalRequestUrl());
    if (resourceTypeToBinaryIds != null) {
      results.setResourceTypeToBinaryIds(resourceTypeToBinaryIds);
      resourceTypeToBinaryIds = null;
    }

    theDataSink.accept(results);
    return RunOutcome.SUCCESS;
  }

}
