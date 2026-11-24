package com.lantanagroup.pdex.bulkmembermatch;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ca.uhn.fhir.batch2.api.VoidModel;
import ca.uhn.fhir.batch2.jobs.export.models.BulkExportBinaryFileId;
import ca.uhn.fhir.batch2.model.JobDefinition;
import ca.uhn.fhir.jpa.api.model.BulkExportJobResults;
import ca.uhn.fhir.model.api.IModelJson;

@Configuration
public class BulkMemberMatchCtx {

  public static final String BULK_MEMBER_MATCH = "BULK_MEMBER_MATCH";

  @Bean
  public JobDefinition<BulkMemberMatchJobParameters> bulkMemberMatchJobDefinition() {

    JobDefinition.Builder<IModelJson, VoidModel> builder = JobDefinition.newBuilder();
    builder.setJobDefinitionId(BULK_MEMBER_MATCH);
    builder.setJobDescription("PDex Bulk Member Match");
    builder.setJobDefinitionVersion(1);

    return builder.setParametersType(BulkMemberMatchJobParameters.class)
        .gatedExecution()
        .addFirstStep(
            "match-members",
            "Matches submitted members and writes result Groups to an ndjson Binary",
            BulkExportBinaryFileId.class,
            bulkMemberMatchStep())
        .addFinalReducerStep(
            "create-report",
            "Creates the completion manifest report",
            BulkExportJobResults.class,
            bulkMemberMatchReportStep())
        .build();
  }

  @Bean
  public BulkMemberMatchStep bulkMemberMatchStep() {
    return new BulkMemberMatchStep();
  }

  @Bean
  public BulkMemberMatchReportStep bulkMemberMatchReportStep() {
    return new BulkMemberMatchReportStep();
  }

}
