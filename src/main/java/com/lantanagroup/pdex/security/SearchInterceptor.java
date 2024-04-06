package com.lantanagroup.pdex.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizedList;
import ca.uhn.fhir.rest.server.interceptor.auth.SearchNarrowingInterceptor;

@Interceptor
public class SearchInterceptor extends SearchNarrowingInterceptor {

  private final Logger myLogger = LoggerFactory.getLogger(SearchInterceptor.class);

  AppProperties appProperties;
  SecurityProperties securityProperties;

  public SearchInterceptor(AppProperties appProperties, SecurityProperties securityProperties) {
    this.appProperties = appProperties;
    this.securityProperties = securityProperties;
  }
  
  @Override
  protected AuthorizedList buildAuthorizedList(RequestDetails theRequestDetails) {
    // TODO: Implement search narrowing
    return new AuthorizedList();
  }

}
