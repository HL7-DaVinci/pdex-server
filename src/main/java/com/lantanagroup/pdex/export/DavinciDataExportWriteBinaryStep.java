package com.lantanagroup.pdex.export;

import org.springframework.beans.factory.annotation.Autowired;

import ca.uhn.fhir.batch2.api.IJobDataSink;
import ca.uhn.fhir.batch2.api.IJobStepWorker;
import ca.uhn.fhir.batch2.api.JobExecutionFailedException;
import ca.uhn.fhir.batch2.api.RunOutcome;
import ca.uhn.fhir.batch2.api.StepExecutionDetails;
import ca.uhn.fhir.batch2.jobs.export.WriteBinaryStep;
import ca.uhn.fhir.batch2.jobs.export.models.BulkExportBinaryFileId;
import ca.uhn.fhir.batch2.jobs.export.models.ExpandedResourcesList;
import ca.uhn.fhir.batch2.model.JobInstance;
import ca.uhn.fhir.rest.api.server.bulk.BulkExportJobParameters;

public class DavinciDataExportWriteBinaryStep
    implements IJobStepWorker<DavinciDataExportJobParameters, ExpandedResourcesList, BulkExportBinaryFileId> {

  @Autowired
  private WriteBinaryStep writeBinaryStep;

  @Override
  public RunOutcome run(
      StepExecutionDetails<DavinciDataExportJobParameters, ExpandedResourcesList> theStepExecutionDetails,
      IJobDataSink<BulkExportBinaryFileId> theDataSink) throws JobExecutionFailedException {

    StepExecutionDetails<BulkExportJobParameters, ExpandedResourcesList> executionDetails = new StepExecutionDetails<BulkExportJobParameters, ExpandedResourcesList>(
        theStepExecutionDetails.getParameters(),
        theStepExecutionDetails.getData(),
        (JobInstance) theStepExecutionDetails.getInstance(),
        theStepExecutionDetails.getChunkId());

    return writeBinaryStep.run(executionDetails, theDataSink);
  }

}
