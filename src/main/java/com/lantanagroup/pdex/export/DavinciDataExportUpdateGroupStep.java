package com.lantanagroup.pdex.export;

import java.util.TimeZone;

import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Group;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Type;
import org.hl7.fhir.r4.model.Group.GroupMemberComponent;
import org.springframework.beans.factory.annotation.Autowired;

import ca.uhn.fhir.batch2.api.IJobDataSink;
import ca.uhn.fhir.batch2.api.IJobStepWorker;
import ca.uhn.fhir.batch2.api.JobExecutionFailedException;
import ca.uhn.fhir.batch2.api.RunOutcome;
import ca.uhn.fhir.batch2.api.StepExecutionDetails;
import ca.uhn.fhir.batch2.jobs.export.models.ExpandedResourcesList;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.api.server.bulk.BulkExportJobParameters.ExportStyle;

public class DavinciDataExportUpdateGroupStep implements IJobStepWorker<DavinciDataExportJobParameters, ExpandedResourcesList, ExpandedResourcesList> {

  private static final String LAST_TRANSMISSION_EXTENSION = "http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/base-ext-last-transmission";
  private static final String LAST_TYPES_EXTENSION = "http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/base-ext-last-types";
  private static final String LAST_FILTERS_EXTENSION = "http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/base-ext-last-typefilter";
  

  @Autowired
	private DaoRegistry myDaoRegistry;

  @Override
  public RunOutcome run(StepExecutionDetails<DavinciDataExportJobParameters, ExpandedResourcesList> theStepExecutionDetails,
      IJobDataSink<ExpandedResourcesList> theDataSink) throws JobExecutionFailedException {

    // If the export style is not GROUP, then we don't need to do anything
    if (theStepExecutionDetails.getParameters().getExportStyle() != ExportStyle.GROUP) {
      return RunOutcome.SUCCESS;
    }

    IFhirResourceDao<Group> groupDao = myDaoRegistry.getResourceDao(Group.class);

    IdType groupId = new IdType(theStepExecutionDetails.getParameters().getGroupId());
    Group group = groupDao.read(groupId, new SystemRequestDetails());

    DateTimeType newDate = DateTimeType.now();
    newDate.setTimeZone(TimeZone.getTimeZone("UTC"));
    StringType newLastTypes = new StringType(String.join(",", theStepExecutionDetails.getParameters().getResourceTypes()));
    StringType newFilters = new StringType(
        theStepExecutionDetails.getParameters().getOriginalTypeFilters() != null
            ? String.join(",", theStepExecutionDetails.getParameters().getOriginalTypeFilters())
            : "");

    for (GroupMemberComponent member : group.getMember()) {
      setMemberExtension(member, LAST_TRANSMISSION_EXTENSION, newDate);
      setMemberExtension(member, LAST_TYPES_EXTENSION, newLastTypes);
      setMemberExtension(member, LAST_FILTERS_EXTENSION, newFilters);
    }

    groupDao.update(group, new SystemRequestDetails());

    theDataSink.accept(theStepExecutionDetails.getData());
    return RunOutcome.SUCCESS;
      
  }


  protected void setMemberExtension(GroupMemberComponent member, String extensionUrl, Type value) {
    member.removeExtension(extensionUrl);
    member.addExtension(new Extension(extensionUrl, value));
  }
  
}
