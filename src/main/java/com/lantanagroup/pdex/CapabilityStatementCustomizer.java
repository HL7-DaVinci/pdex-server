package com.lantanagroup.pdex;

import org.hl7.fhir.instance.model.api.IBaseConformance;
import org.hl7.fhir.r4.model.CapabilityStatement;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;

@Interceptor
public class CapabilityStatementCustomizer {

  AppProperties appProperties;

  public CapabilityStatementCustomizer(AppProperties appProperties) {
    this.appProperties = appProperties;
  }
  
  @Hook(Pointcut.SERVER_CAPABILITY_STATEMENT_GENERATED)
  public void customize(IBaseConformance theCapabilityStatement, ServletRequestDetails theRequest) {

    CapabilityStatement cs = (CapabilityStatement) theCapabilityStatement;

    cs.getSoftware().setName("PDex Server");

  }

}
