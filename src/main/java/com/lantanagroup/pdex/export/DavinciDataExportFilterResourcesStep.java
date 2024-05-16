package com.lantanagroup.pdex.export;

import org.springframework.beans.factory.annotation.Autowired;

import ca.uhn.fhir.batch2.api.IFirstJobStepWorker;
import ca.uhn.fhir.batch2.api.IJobDataSink;
import ca.uhn.fhir.batch2.api.JobExecutionFailedException;
import ca.uhn.fhir.batch2.api.RunOutcome;
import ca.uhn.fhir.batch2.api.StepExecutionDetails;
import ca.uhn.fhir.batch2.api.VoidModel;
import ca.uhn.fhir.batch2.jobs.export.FetchResourceIdsStep;
import ca.uhn.fhir.batch2.jobs.export.models.ResourceIdList;
import ca.uhn.fhir.batch2.model.JobInstance;
import ca.uhn.fhir.rest.api.server.bulk.BulkExportJobParameters;

public class DavinciDataExportFilterResourcesStep implements IFirstJobStepWorker<DavinciDataExportJobParameters, ResourceIdList> {

  @Autowired
  private FetchResourceIdsStep fetchResourceIdsStep;
  

  @Override
  public RunOutcome run(StepExecutionDetails<DavinciDataExportJobParameters, VoidModel> theStepExecutionDetails,
      IJobDataSink<ResourceIdList> theDataSink) throws JobExecutionFailedException {

    StepExecutionDetails<BulkExportJobParameters, VoidModel> executionDetails = new StepExecutionDetails<BulkExportJobParameters, VoidModel>(
        theStepExecutionDetails.getParameters(),
        null,
        (JobInstance) theStepExecutionDetails.getInstance(),
        theStepExecutionDetails.getChunkId());

    return fetchResourceIdsStep.run(executionDetails, theDataSink);
  }

}
