package com.lantanagroup.pdex.export;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ca.uhn.fhir.batch2.api.IJobStepWorker;
import ca.uhn.fhir.batch2.api.VoidModel;
import ca.uhn.fhir.batch2.jobs.export.models.BulkExportBinaryFileId;
import ca.uhn.fhir.batch2.jobs.export.models.BulkExportWorkPackageJson;
import ca.uhn.fhir.batch2.jobs.export.models.ResourceIdList;
import ca.uhn.fhir.batch2.jobs.export.v3.ExpandResourceAndWriteBinaryStep;
import ca.uhn.fhir.batch2.jobs.export.v3.FetchResourceIdsV3Step;
import ca.uhn.fhir.batch2.jobs.export.v3.GenerateWorkPackagesV3Step;
import ca.uhn.fhir.batch2.model.JobDefinition;
import ca.uhn.fhir.jpa.api.model.BulkExportJobResults;
import ca.uhn.fhir.model.api.IModelJson;

@Configuration
public class DavinciDataExportCtx {

  public static final String DAVINCI_DATA_EXPORT = "DAVINCI_DATA_EXPORT";
  
  @Bean
  @SuppressWarnings({"unchecked", "rawtypes"})
  public JobDefinition<DavinciDataExportJobParameters> davinciDataExportJobDefinition(
      GenerateWorkPackagesV3Step generateWorkPackagesStep,
      FetchResourceIdsV3Step fetchResourceIdsStep,
      @Qualifier("bulkExportV3ExpandResourceAndWriteBinaryStep") ExpandResourceAndWriteBinaryStep expandResourceAndWriteBinaryStep) {

    JobDefinition.Builder<IModelJson, VoidModel> builder = JobDefinition.newBuilder();
    builder.setJobDefinitionId(DAVINCI_DATA_EXPORT);
    builder.setJobDescription("DaVinci Data Export");
    builder.setJobDefinitionVersion(1);

    return builder.setParametersType(DavinciDataExportJobParameters.class)
    // JobDefinition<BulkExportJobParameters> def = builder.setParametersType(BulkExportJobParameters.class)
      // .setParametersValidator(bulkExportJobParametersValidator())
      .gatedExecution()
      // first step - load in (all) ids and create id chunks of 1000 each
      // .addFirstStep(
      //   "fetch-resources",
      //   "Fetches resource PIDs for exporting",
      //   ResourceIdList.class,
      //   fetchResourceIdsStep)
      // the steps below are the same steps HAPI uses for its own bulk export job (version 3)
      .addFirstStep(
        "generate-work-packages",
        "Generates the work packages for exporting",
        BulkExportWorkPackageJson.class,
        (IJobStepWorker) generateWorkPackagesStep)
      .addIntermediateStep(
        "fetch-resources",
        "Fetches resource PIDs for exporting",
        ResourceIdList.class,
        (IJobStepWorker) fetchResourceIdsStep)
      // expand out the resources, then write binaries and save to db
      .addIntermediateStep(
        "write-to-binaries",
        "Writes the expanded resources to the binaries and saves",
        BulkExportBinaryFileId.class,
        (IJobStepWorker) expandResourceAndWriteBinaryStep)

      // new step to tag the exported group members with required extensions:
      // http://build.fhir.org/ig/HL7/davinci-epdx/provider-access-api.html#attribution-list
      // It runs after the resources are expanded, so an exported Group keeps its previous extensions.
      .addIntermediateStep(
        "update-group",
        "Updates the exported group members with metadata from this export operation",
        BulkExportBinaryFileId.class,
        davinciDataExportUpdateGroupStep()
      )

      // finalize the job (set to complete)
      .addFinalReducerStep(
        "create-report-step",
        "Creates the output report from a bulk export job",
        BulkExportJobResults.class,
        davinciDataExportCreateReportStep())
      .build();

  }


  @Bean
  public DavinciDataExportUpdateGroupStep davinciDataExportUpdateGroupStep() {
    return new DavinciDataExportUpdateGroupStep();
  }

  @Bean
  public DavinciDataExportCreateReportStep davinciDataExportCreateReportStep() {
    return new DavinciDataExportCreateReportStep();
  }

}
