package com.lantanagroup.pdex.security;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.starter.AppProperties;

@Interceptor
public class SmartDiscoveryInterceptor {

  private final Logger myLogger = LoggerFactory.getLogger(SmartDiscoveryInterceptor.class);

  AppProperties appProperties;
  SecurityProperties securityProperties;

  public SmartDiscoveryInterceptor(AppProperties appProperties, SecurityProperties securityProperties) {
    this.appProperties = appProperties;
    this.securityProperties = securityProperties;
  }


  @Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_PROCESSED)
  public boolean incomingRequestPreProcessed(HttpServletRequest theRequest, HttpServletResponse theResponse) throws IOException {

    if (!theRequest.getRequestURI().equals("/fhir/.well-known/smart-configuration")) {
      return true;
    }

    // myLogger.info("Intercepted request to /fhir/.well-known/smart-configuration");

    SmartConfigurationObject smartConfig = new SmartConfigurationObject();
    smartConfig.setAuthorization_endpoint(securityProperties.getAuthorizationUrl());
    smartConfig.setGrant_types_supported(new String[] { "authorization_code", "client_credentials" });
    smartConfig.setToken_endpoint(securityProperties.getTokenUrl());
    smartConfig.setCode_challenge_methods_supported(new String[] { "S256" });

    theResponse.setContentType("application/json");
    ObjectMapper mapper = new ObjectMapper();
    mapper.writeValue(theResponse.getOutputStream(), smartConfig);


    return false;
  }
  
}
