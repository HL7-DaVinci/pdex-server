package com.lantanagroup.pdex.resourceProvider;

import org.hl7.fhir.r4.model.Group;
import org.hl7.fhir.r4.model.Parameters;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.server.exceptions.NotImplementedOperationException;

public class BulkMemberMatchProvider {

  FhirContext ctx;
  DaoRegistry daoRegistry;

  public BulkMemberMatchProvider(FhirContext ctx, DaoRegistry daoRegistry) {
    this.ctx = ctx;
    this.daoRegistry = daoRegistry;
  }

  @Operation(name = "$bulk-member-match", type = Group.class)
  public Parameters bulkMemberMatch(
    @OperationParam(name = "MatchRequest", min = 1, max = 1, type = Parameters.class) Parameters theMatchrequest
  ) {
    // TODO: Implement operation $bulk-member-match
    throw new NotImplementedOperationException("Operation $bulk-member-match is not implemented");
    
    // Parameters retVal = new Parameters();
    // return retVal;
    
  }

  
}
