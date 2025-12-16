package com.lantanagroup.pdex.security;

import java.util.List;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;

import org.hl7.fhir.r4.model.IdType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizationInterceptor;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRule;
import ca.uhn.fhir.rest.server.interceptor.auth.RuleBuilder;

@Interceptor
public class AuthInterceptor extends AuthorizationInterceptor {

  private final Logger myLogger = LoggerFactory.getLogger(AuthInterceptor.class);

  AppProperties appProperties;
  SecurityProperties securityProperties;

  public AuthInterceptor(AppProperties appProperties, SecurityProperties securityProperties) {
    this.appProperties = appProperties;
    this.securityProperties = securityProperties;
  }

  @Override
  public List<IAuthRule> buildRuleList(RequestDetails theRequestDetails) {

    // Bypass all auth checks if configured to do so
    if (AuthUtil.bypassAuth(theRequestDetails, securityProperties)) {
      //myLogger.debug("Bypassing auth checks for request to {}", theRequestDetails.getCompleteUrl());
      return new RuleBuilder().allowAll().build();
    }

    // Get the token from the request (this also validates the token)
    DecodedJWT jwt = AuthUtil.getToken(theRequestDetails, securityProperties);

    // If configuration does not require patient claim, allow all access
    if (!securityProperties.isPatientClaimRequired()) {
      return new RuleBuilder().allowAll().build();
    }

    var ruleBuilder = new RuleBuilder().allow().metadata();

    if (jwt != null) {

      // Check if this is a patient
      Claim patientClaim = jwt.getClaim("patient");
      if (patientClaim != null && !patientClaim.isNull() && !patientClaim.isMissing()) {
        IdType patientId = new IdType("Patient", patientClaim.asString());
        // TODO: scope checks
        ruleBuilder = ruleBuilder
          .andThen().allow().read().allResources().inCompartment("Patient", patientId)
          .andThen().allow().write().allResources().inCompartment("Patient", patientId);
      } else {
        myLogger.info("No patient claim found in token");
      }

    }

    ruleBuilder = ruleBuilder.andThen().denyAll();
    return ruleBuilder.build();
  }
  
  
  
  
}
