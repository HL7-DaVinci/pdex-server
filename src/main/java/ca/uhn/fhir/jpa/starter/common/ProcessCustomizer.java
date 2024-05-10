package ca.uhn.fhir.jpa.starter.common;

import java.io.InputStream;
import java.util.List;
import java.util.ArrayList;

import org.hl7.fhir.instance.model.api.IBaseConformance;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import javax.servlet.http.HttpServletRequest;

import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.parser.IParser;
import org.hl7.fhir.r4.model.*;

import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.param.*;
import ca.uhn.fhir.rest.api.server.IBundleProvider;

@Interceptor
public class ProcessCustomizer {

	private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ProcessCustomizer.class);

	protected FhirContext fhirContext;
	protected DaoRegistry theDaoRegistry;
	protected IFhirResourceDao<Task> theTaskDao;
	protected String key;
	private boolean dataLoaded;
	private IParser jparser;

	public ProcessCustomizer(FhirContext fhirContext, DaoRegistry theDaoRegistry) {
		dataLoaded = false;
		this.fhirContext = fhirContext;
		this.theDaoRegistry = theDaoRegistry;

		theTaskDao = this.theDaoRegistry.getResourceDao(Task.class);

		jparser = fhirContext.newJsonParser();
		jparser.setPrettyPrint(true);
	}

	// If task is already completed or rejected The task may not be updated (Perhaps
	// could be handled through specialized permissions?)
	@Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLER_SELECTED)
	public void customizeProcessIncomingRequest(RequestDetails theRequestDetails, HttpServletRequest theServletRequest) {
		if (theRequestDetails != null) {
			if (!dataLoaded) {
				dataLoaded = true;
				logger.info("First request made to Server");
				logger.info("Loading all data");

				for (String filename : getServerResources("ri_resources", "CodeSystem-*.json")) {
					try {
						System.out.println("Uploading resource " + filename);
						theDaoRegistry.getResourceDao(CodeSystem.class).update(
								jparser.parseResource(CodeSystem.class, util.loadResource(filename)), theRequestDetails);
					} catch (Exception e) {
						System.out.println("Failure to update the CodeSystem: " + e.getMessage());
					}
				}

				for (String filename : getServerResources("ri_resources", "ValueSet-*.json")) {
					try {
						System.out.println("Uploading resource " + filename);
						theDaoRegistry.getResourceDao(ValueSet.class).update(
								jparser.parseResource(ValueSet.class, util.loadResource(filename)), theRequestDetails);
					} catch (Exception e) {
						System.out.println("Failure to update the ValueSet: " + e.getMessage());
					}
				}

				for (String filename : getServerResources("ri_resources", "StructureDefinition-*.json")) {
					try {
						System.out.println("Uploading resource " + filename);
						theDaoRegistry.getResourceDao(StructureDefinition.class).update(
								jparser.parseResource(StructureDefinition.class, util.loadResource(filename)), theRequestDetails);
					} catch (Exception e) {
						System.out.println("Failure to update the StructureDefinition: " + e.getMessage());
					}
				}
				for (String filename : getServerResources("ri_resources", "Parameters-*.json")) {
					try {
						System.out.println("Uploading resource " + filename);
						theDaoRegistry.getResourceDao(Parameters.class).update(
								jparser.parseResource(Parameters.class, util.loadResource(filename)), theRequestDetails);
					} catch (Exception e) {
						System.out.println("Failure to update the Parameters: " + e.getMessage());
					}
				}
				for (String filename : getServerResources("ri_resources", "SearchParameter-*.json")) {
					try {
						System.out.println("Uploading resource " + filename);
						theDaoRegistry.getResourceDao(SearchParameter.class).update(
								jparser.parseResource(SearchParameter.class, util.loadResource(filename)), theRequestDetails);
					} catch (Exception e) {
						System.out.println("Failure to update the SearchParameter: " + e.getMessage());
					}
				}
				for (String filename : getServerResources("ri_resources", "OperationDefinition-*.json")) {
					try {
						System.out.println("Uploading resource " + filename);
						theDaoRegistry.getResourceDao(OperationDefinition.class).update(
								jparser.parseResource(OperationDefinition.class, util.loadResource(filename)), theRequestDetails);
					} catch (Exception e) {
						System.out.println("Failure to update the OperationDefinition: " + e.getMessage());
					}
				}
				for (String filename : getServerResources("ri_resources", "CapabilityStatement-*.json")) {
					try {
						System.out.println("Uploading resource " + filename);
						theDaoRegistry.getResourceDao(CapabilityStatement.class).update(
								jparser.parseResource(CapabilityStatement.class, util.loadResource(filename)), theRequestDetails);
					} catch (Exception e) {
						System.out.println("Failure to update the CapabilityStatement: " + e.getMessage());
					}
				}


				


				for (String filename : getServerResources("ri_resources", "Organization-*.json")) {
					try {
						System.out.println("Uploading resource " + filename);
						theDaoRegistry.getResourceDao(Organization.class).update(
								jparser.parseResource(Organization.class, util.loadResource(filename)), theRequestDetails);
					} catch (Exception e) {
						System.out.println("Failure to update the Organization: " + e.getMessage());
					}
				}

				for (String filename : getServerResources("ri_resources", "Practitioner-*.json")) {
					try {
						System.out.println("Uploading resource " + filename);
						theDaoRegistry.getResourceDao(Practitioner.class).update(
								jparser.parseResource(Practitioner.class, util.loadResource(filename)), theRequestDetails);
					} catch (Exception e) {
						System.out.println("Failure to update the Practitioner: " + e.getMessage());
					}
				}
				for (String filename : getServerResources("ri_resources", "PractitionerRole-*.json")) {
					try {
						System.out.println("Uploading resource " + filename);
						theDaoRegistry.getResourceDao(PractitionerRole.class).update(
								jparser.parseResource(PractitionerRole.class, util.loadResource(filename)),
								theRequestDetails);
					} catch (Exception e) {
						System.out.println("Failure to update the PractitionerRole: " + e.getMessage());
					}
				}
				for (String filename : getServerResources("ri_resources", "Endpoint-*.json")) {
					try {
						System.out.println("Uploading resource " + filename);
						theDaoRegistry.getResourceDao(Endpoint.class).update(
								jparser.parseResource(Endpoint.class, util.loadResource(filename)), theRequestDetails);
					} catch (Exception e) {
						System.out.println("Failure to update the Endpoint: " + e.getMessage());
					}
				}
				for (String filename : getServerResources("ri_resources", "Location-*.json")) {
					try {
						System.out.println("Uploading resource " + filename);
						theDaoRegistry.getResourceDao(Location.class).update(
								jparser.parseResource(Location.class, util.loadResource(filename)), theRequestDetails);
					} catch (Exception e) {
						System.out.println("Failure to update the Location: " + e.getMessage());
					}
				}
				for (String filename : getServerResources("ri_resources", "Patient-*.json")) {
					try {
						System.out.println("Uploading resource " + filename);
						theDaoRegistry.getResourceDao(Patient.class).update(
								jparser.parseResource(Patient.class, util.loadResource(filename)), theRequestDetails);
					} catch (Exception e) {
						System.out.println("Failure to update the Patient: " + e.getMessage());
					}
				}
				for (String filename : getServerResources("ri_resources", "Coverage-*.json")) {
					try {
						System.out.println("Uploading resource " + filename);
						theDaoRegistry.getResourceDao(Coverage.class).update(
								jparser.parseResource(Coverage.class, util.loadResource(filename)), theRequestDetails);
					} catch (Exception e) {
						System.out.println("Failure to update the Coverage: " + e.getMessage());
					}
				}
				for (String filename : getServerResources("ri_resources", "Encounter-*.json")) {
					try {
						System.out.println("Uploading resource " + filename);
						theDaoRegistry.getResourceDao(Encounter.class).update(
								jparser.parseResource(Encounter.class, util.loadResource(filename)), theRequestDetails);
					} catch (Exception e) {
						System.out.println("Failure to update the Encounter: " + e.getMessage());
					}
				}
				for (String filename : getServerResources("ri_resources", "Device-*.json")) {
					try {
						System.out.println("Uploading resource " + filename);
						theDaoRegistry.getResourceDao(Device.class).update(
								jparser.parseResource(Device.class, util.loadResource(filename)), theRequestDetails);
					} catch (Exception e) {
						System.out.println("Failure to update the Device: " + e.getMessage());
					}
				}
				for (String filename : getServerResources("ri_resources", "DocumentReference-*.json")) {
					try {
						System.out.println("Uploading resource " + filename);
						theDaoRegistry.getResourceDao(DocumentReference.class).update(
								jparser.parseResource(DocumentReference.class, util.loadResource(filename)), theRequestDetails);
					} catch (Exception e) {
						System.out.println("Failure to update the DocumentReference: " + e.getMessage());
					}
				}
				for (String filename : getServerResources("ri_resources", "Consent-*.json")) {
					try {
						System.out.println("Uploading resource " + filename);
						theDaoRegistry.getResourceDao(Consent.class).update(
								jparser.parseResource(Consent.class, util.loadResource(filename)), theRequestDetails);
					} catch (Exception e) {
						System.out.println("Failure to update the Consent: " + e.getMessage());
					}
				}
				for (String filename : getServerResources("ri_resources", "Medication-*.json")) {
					try {
						System.out.println("Uploading resource " + filename);
						theDaoRegistry.getResourceDao(Medication.class).update(
								jparser.parseResource(Medication.class, util.loadResource(filename)), theRequestDetails);
					} catch (Exception e) {
						System.out.println("Failure to update the Medication: " + e.getMessage());
					}
				}
				for (String filename : getServerResources("ri_resources", "MedicationDispense-*.json")) {
					try {
						System.out.println("Uploading resource " + filename);
						theDaoRegistry.getResourceDao(MedicationDispense.class).update(
								jparser.parseResource(MedicationDispense.class, util.loadResource(filename)), theRequestDetails);
					} catch (Exception e) {
						System.out.println("Failure to update the MedicationDispense: " + e.getMessage());
					}
				}
				for (String filename : getServerResources("ri_resources", "ExplanationOfBenefit-*.json")) {
					try {
						System.out.println("Uploading resource " + filename);
						theDaoRegistry.getResourceDao(ExplanationOfBenefit.class).update(
								jparser.parseResource(ExplanationOfBenefit.class, util.loadResource(filename)), theRequestDetails);
					} catch (Exception e) {
						System.out.println("Failure to update the ExplanationOfBenefit: " + e.getMessage());
					}
				}






				for (String filename : getServerResources("ri_resources", "Group-*.json")) {
					try {
						System.out.println("Uploading resource " + filename);
						theDaoRegistry.getResourceDao(Group.class).update(
								jparser.parseResource(Group.class, util.loadResource(filename)), theRequestDetails);
					} catch (Exception e) {
						System.out.println("Failure to update the Group: " + e.getMessage());
					}
				}
				for (String filename : getServerResources("ri_resources", "Provenance-*.json")) {
					try {
						System.out.println("Uploading resource " + filename);
						theDaoRegistry.getResourceDao(Provenance.class).update(
								jparser.parseResource(Provenance.class, util.loadResource(filename)),
								theRequestDetails);
					} catch (Exception e) {
						System.out.println("Failure to update the Provenance: " + e.getMessage());
					}
				}

				
				for (String filename : getServerResources("ri_resources", "Bundle-*.json")) {
					try {
						System.out.println("Uploading resource " + filename);
						theDaoRegistry.getResourceDao(Bundle.class).update(
								jparser.parseResource(Bundle.class, util.loadResource(filename)), theRequestDetails);
					} catch (Exception e) {
						System.out.println("Failure to update the Bundle: " + e.getMessage());
					}
				}
				
				// Attempt to load provenance again because some reference Bundles (also some bundlkes reference Provenance)
				for (String filename : getServerResources("ri_resources", "Provenance-*.json")) {
					try {
						System.out.println("Uploading resource " + filename);
						theDaoRegistry.getResourceDao(Provenance.class).update(
								jparser.parseResource(Provenance.class, util.loadResource(filename)),
								theRequestDetails);
					} catch (Exception e) {
						System.out.println("Failure to update the Provenance: " + e.getMessage());
					}
				}

				
				for (String filename : getServerResources("ri_resources", "ImplementationGuide-*.json")) {
					try {
						System.out.println("Uploading resource " + filename);
						theDaoRegistry.getResourceDao(ImplementationGuide.class).update(
								jparser.parseResource(ImplementationGuide.class, util.loadResource(filename)),
								theRequestDetails);
					} catch (Exception e) {
						System.out.println("Failure to update the ImplementationGuide: " + e.getMessage());
					}
				}
			}
			// if overriding requirement rules, just skip

			if (theServletRequest != null && theServletRequest.getHeader("X-Override") != null
					&& theServletRequest.getHeader("X-Override").equalsIgnoreCase("true")) {
				return;
			}
			/*
			 * Add code to disable uny unallowed actions
			 *
			 * if(theRequestDetails.getRequestType() == RequestTypeEnum.POST ||
			 * theRequestDetails.getRequestType() == RequestTypeEnum.PUT ||
			 * theRequestDetails.getRequestType() == RequestTypeEnum.PATCH)
			 * {
			 * 
			 * if(theRequestDetails.getResource() != null &&
			 * theRequestDetails.getResourceName().equals("Task"))
			 * {
			 * Task existingTask = null;
			 * try{
			 * // Get existing task to see if the status is rejected or completed
			 * existingTask = theTaskDao.read(theRequestDetails.getId(), theRequestDetails);
			 * 
			 * 
			 * }
			 * catch(Exception e)
			 * {
			 * // Unable to retrieve existing resource, allow to continue to process as
			 * normal
			 * logger.
			 * error("Unable to retrieve existing Task resource or otherwise check it's status when preprocessing request, allow to continue to process as normal"
			 * , e);
			 * 
			 * }
			 * if(existingTask != null)
			 * {
			 * if(existingTask.hasStatus() && (existingTask.getStatus() ==
			 * Task.TaskStatus.CANCELLED ||
			 * existingTask.getStatus() == Task.TaskStatus.COMPLETED ||
			 * existingTask.getStatus() == Task.TaskStatus.REJECTED ||
			 * existingTask.getStatus() == Task.TaskStatus.ENTEREDINERROR))
			 * {
			 * throw new
			 * ForbiddenOperationException("Task may not be updated. Existing Tasks with the status '"
			 * + existingTask.getStatus().toCode() + "' cannot be modified.");
			 * }
			 * }
			 * }
			 * }
			 */
		}
	}

	@Hook(Pointcut.SERVER_PROCESSING_COMPLETED_NORMALLY)
	public void customizeProcessCompletedNormally(RequestDetails theRequestDetails) {

		/*
		 * Task Status When deemed appropriate, the GFE Coordination Requester SHALL
		 * close the Task by updating the status to completed or cancelled (the choice
		 * of which depends on the intent of the requester) When the status of the
		 * GFE Coordination Task is updated, the Coordination Platform SHALL update the
		 * associated GFE Contributor Task statuses to match, except for those that have
		 * a status of cancelled, rejected, entered-in-error, failed, or completed`.
		 * Need an interceptor for the SERVER_PROCESSING_COMPLETED_NORMALLY PointCut
		 * looking to see if it was a Task where the status was updated (possible?) Or
		 * just make sure all contributor tasks are updated appropriately (Catch any
		 * exceptions)
		 */
		if (theRequestDetails != null) {
			if (theRequestDetails.getRequestType() == RequestTypeEnum.POST
					|| theRequestDetails.getRequestType() == RequestTypeEnum.PUT
					|| theRequestDetails.getRequestType() == RequestTypeEnum.PATCH) {
				if (theRequestDetails.getResourceName().equals("Task")) {
					try {
						// Check the Task type to see if it is a Coordination Task and if the status is
						// cancelled, completed, or entered in error (any others?), change contributors
						// tasks where status is not already completed or rejected (others?)
						Task requestTask = (Task) theRequestDetails.getResource();
						if (requestTask.hasCode() && requestTask.getCode().hasCoding(
								"http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTGFERequestTaskCSTemporaryTrialUse",
								"gfe-coordination-task")) {
							// Not in 2.0.0 ballot version, but contributor tasks should only be updated if
							// coordination task status is (cancelled, on-hold, failed, completed, or
							// entered in error)
							if (requestTask.hasStatus() && (requestTask.getStatus() == Task.TaskStatus.CANCELLED ||
									requestTask.getStatus() == Task.TaskStatus.FAILED ||
									requestTask.getStatus() == Task.TaskStatus.COMPLETED ||
									requestTask.getStatus() == Task.TaskStatus.ENTEREDINERROR)) {

								List<Task> contributorTasks = getContributorTasks(requestTask, theRequestDetails);
								contributorTasks.forEach(task -> {
									// cancelled, rejected, entered-in-error, failed, or completed`
									if (task.hasStatus() && (task.getStatus() != Task.TaskStatus.CANCELLED &&
											task.getStatus() != Task.TaskStatus.REJECTED &&
											task.getStatus() != Task.TaskStatus.ENTEREDINERROR &&
											task.getStatus() != Task.TaskStatus.FAILED &&
											task.getStatus() != Task.TaskStatus.COMPLETED)) {
										task.setStatus(requestTask.getStatus());
										// TODO Should this be update (Put) or patch ?
										theTaskDao.update(task, theRequestDetails);
									}
								});
							}
						}
					} catch (Exception e) {
						// unable to make changes to contributors, log an continue
						logger.error(
								"Unable to process Task status (change contributor task status based on Coordination Task Status), allow to continue to process as normal",
								e);

					}

				}
			}
		}

		/*
		 * String fileName = "CapabilityStatement-" + key + ".json";
		 * 
		 * try {
		 * 
		 * DefaultResourceLoader resourceLoader = new DefaultResourceLoader();
		 * Resource resource = resourceLoader.getResource(fileName);
		 * InputStream inputStream = resource.getInputStream();
		 * 
		 * IBaseConformance capabilityStatement = (IBaseConformance)
		 * fhirContext.newJsonParser().parseResource(inputStream);
		 * 
		 * return capabilityStatement;
		 * 
		 * } catch (Exception e) {
		 * logger.error("Failed to load CapabilityStatment with filename " + fileName +
		 * ": " + e.getMessage(), e);
		 * return null;
		 * }
		 */

	}

	public List<Task> getContributorTasks(Task coordinationTask, RequestDetails theRequestDetails) {
		List<Task> contributorTasks = new ArrayList<>();
		SearchParameterMap searchMap = new SearchParameterMap();
		searchMap.add(Task.PART_OF.getParamName(), new ReferenceParam(coordinationTask.getId()));
		IBundleProvider taskResults = theTaskDao.search(searchMap, theRequestDetails);

		taskResults.getResources(0, taskResults.size())
				.stream().map(Task.class::cast)
				.forEach(task -> contributorTasks.add(task));

		return contributorTasks;
	}

	public List<String> getServerResources(String path, String pattern) {
		List<String> files = new ArrayList<>();

		String localPath = path;
		if (!localPath.substring(localPath.length() - 1, localPath.length() - 1).equals("/")) {
			localPath = localPath + "/";
		}

		try {

			ClassLoader cl = this.getClass().getClassLoader();
			ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(cl);

			org.springframework.core.io.Resource[] resources = resolver
					.getResources("classpath*:" + localPath + pattern);

			for (org.springframework.core.io.Resource resource : resources) {
				files.add(localPath + resource.getFilename());
				logger.info(localPath + resource.getFilename());
			}
		} catch (Exception e) {
			logger.info("Error retrieving file names from " + localPath + pattern);
		}

		return files;
	}

}
