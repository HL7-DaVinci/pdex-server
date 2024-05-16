package com.lantanagroup.pdex.export;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ca.uhn.fhir.batch2.api.VoidModel;
import ca.uhn.fhir.batch2.jobs.export.models.BulkExportBinaryFileId;
import ca.uhn.fhir.batch2.jobs.export.models.ExpandedResourcesList;
import ca.uhn.fhir.batch2.jobs.export.models.ResourceIdList;
import ca.uhn.fhir.batch2.model.JobDefinition;
import ca.uhn.fhir.jpa.api.model.BulkExportJobResults;
import ca.uhn.fhir.model.api.IModelJson;

@Configuration
public class DavinciDataExportCtx {

  public static final String DAVINCI_DATA_EXPORT = "DAVINCI_DATA_EXPORT";

  // @Autowired
  // private FetchResourceIdsStep fetchResourceIdsStep;

  // @Autowired
  // private ExpandResourcesStep expandResourcesStep;

  // @Autowired
  // private WriteBinaryStep writeBinaryStep;

  // @Autowired
  // private BulkExportCreateReportStep createReportStep;

  @Bean
  public JobDefinition<DavinciDataExportJobParameters> davinciDataExportJobDefinition() {

    JobDefinition.Builder<IModelJson, VoidModel> builder = JobDefinition.newBuilder();
    builder.setJobDefinitionId(DAVINCI_DATA_EXPORT);
    builder.setJobDescription("DaVinci Data Export");
    builder.setJobDefinitionVersion(1);

    JobDefinition<DavinciDataExportJobParameters> def = builder.setParametersType(DavinciDataExportJobParameters.class)
    // JobDefinition<BulkExportJobParameters> def = builder.setParametersType(BulkExportJobParameters.class)
      // .setParametersValidator(bulkExportJobParametersValidator())
      .gatedExecution()
      // first step - load in (all) ids and create id chunks of 1000 each
      // .addFirstStep(
      //   "fetch-resources",
      //   "Fetches resource PIDs for exporting",
      //   ResourceIdList.class,
      //   fetchResourceIdsStep)
      .addFirstStep(
        "fetch-resources",
        "Fetches resource PIDs for exporting",
        ResourceIdList.class,
        davinciDataExportFilterResourcesStep())
      // expand out - fetch resources
      .addIntermediateStep(
        "expand-resources",
        "Expand out resources",
        ExpandedResourcesList.class,
        davinciDataExportExpandResourcesStep())

      // new step to tag the exported group members with required extensions:
      // http://build.fhir.org/ig/HL7/davinci-epdx/provider-access-api.html#attribution-list
      .addIntermediateStep(
        "update-group",
        "Updates the exported group members with metadata from this export operation",
        ExpandedResourcesList.class,
        davinciDataExportUpdateGroupStep()
      )

      // write binaries and save to db
      .addIntermediateStep(
        "write-to-binaries",
        "Writes the expanded resources to the binaries and saves",
        BulkExportBinaryFileId.class,
        davinciDataExportWriteBinaryStep())
      // finalize the job (set to complete)
      .addFinalReducerStep(
        "create-report-step",
        "Creates the output report from a bulk export job",
        BulkExportJobResults.class,
        davinciDataExportCreateReportStep())
      .build();

    return def;
  }


  @Bean
  public DavinciDataExportFilterResourcesStep davinciDataExportFilterResourcesStep() {
    return new DavinciDataExportFilterResourcesStep();
  }

  @Bean
  public DavinciDataExportExpandResourcesStep davinciDataExportExpandResourcesStep() {
    return new DavinciDataExportExpandResourcesStep();
  }

  @Bean
  public DavinciDataExportUpdateGroupStep davinciDataExportUpdateGroupStep() {
    return new DavinciDataExportUpdateGroupStep();
  }

  @Bean
  public DavinciDataExportWriteBinaryStep davinciDataExportWriteBinaryStep() {
    return new DavinciDataExportWriteBinaryStep();
  }

  @Bean
  public DavinciDataExportCreateReportStep davinciDataExportCreateReportStep() {
    return new DavinciDataExportCreateReportStep();
  }

}
