package com.lantanagroup.pdex.export;

import org.springframework.beans.factory.annotation.Autowired;

import ca.uhn.fhir.batch2.api.IJobDataSink;
import ca.uhn.fhir.batch2.api.IJobStepWorker;
import ca.uhn.fhir.batch2.api.JobExecutionFailedException;
import ca.uhn.fhir.batch2.api.RunOutcome;
import ca.uhn.fhir.batch2.api.StepExecutionDetails;
import ca.uhn.fhir.batch2.jobs.export.ExpandResourcesStep;
import ca.uhn.fhir.batch2.jobs.export.models.ExpandedResourcesList;
import ca.uhn.fhir.batch2.jobs.export.models.ResourceIdList;
import ca.uhn.fhir.batch2.model.JobInstance;
import ca.uhn.fhir.rest.api.server.bulk.BulkExportJobParameters;

public class DavinciDataExportExpandResourcesStep implements IJobStepWorker<DavinciDataExportJobParameters, ResourceIdList, ExpandedResourcesList> {

  @Autowired
  private ExpandResourcesStep expandResourcesStep;

  @Override
  public RunOutcome run(StepExecutionDetails<DavinciDataExportJobParameters, ResourceIdList> theStepExecutionDetails,
      IJobDataSink<ExpandedResourcesList> theDataSink) throws JobExecutionFailedException {
    
      StepExecutionDetails<BulkExportJobParameters, ResourceIdList> executionDetails = new StepExecutionDetails<BulkExportJobParameters, ResourceIdList>(
        theStepExecutionDetails.getParameters(),
        theStepExecutionDetails.getData(),
        (JobInstance) theStepExecutionDetails.getInstance(),
        theStepExecutionDetails.getChunkId());

    return expandResourcesStep.run(executionDetails, theDataSink);
  }
  
}
