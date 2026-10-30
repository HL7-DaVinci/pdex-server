package com.lantanagroup.pdex;

import java.util.Optional;

import org.springframework.stereotype.Component;

import ca.uhn.fhir.batch2.api.IJobCoordinator;
import ca.uhn.fhir.batch2.jobs.export.BulkDataExportProvider;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.partition.IRequestPartitionHelperSvc;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.interceptor.CorsInterceptor;
import com.lantanagroup.pdex.bulkmembermatch.BulkMemberMatchProvider;
import com.lantanagroup.pdex.export.DavinciDataExportProvider;
import com.lantanagroup.pdex.resourceProvider.MemberMatchProvider;
import com.lantanagroup.pdex.security.AuthInterceptor;
import com.lantanagroup.pdex.security.DiscoveryInterceptor;
import com.lantanagroup.pdex.security.SearchInterceptor;
import com.lantanagroup.pdex.security.SecurityProperties;
import com.lantanagroup.pdex.security.SmartDiscoveryInterceptor;

/**
 * Registers the PDex operations and interceptors on the RestfulServer that the HAPI starter builds.
 * Runs during context startup, before the servlet container starts serving requests.
 */
@Component
public class PdexServerCustomizer {

  public PdexServerCustomizer(
      RestfulServer server,
      DaoRegistry daoRegistry,
      IJobCoordinator jobCoordinator,
      BulkDataExportProvider bulkDataExportProvider,
      IRequestPartitionHelperSvc requestPartitionHelperSvc,
      MemberMatchProvider memberMatchProvider,
      AppProperties appProperties,
      SecurityProperties securityProperties,
      Optional<CorsInterceptor> corsInterceptor) {

    FhirContext ctx = server.getFhirContext();
    server.registerProvider(memberMatchProvider);
    server.registerProvider(new BulkMemberMatchProvider(ctx, daoRegistry, jobCoordinator));
    server.registerProvider(new DavinciDataExportProvider(ctx, daoRegistry, jobCoordinator, bulkDataExportProvider, requestPartitionHelperSvc));

    server.registerInterceptor(new CapabilityStatementCustomizer(appProperties, securityProperties));
    server.registerInterceptor(new SmartDiscoveryInterceptor(appProperties, securityProperties));
    server.registerInterceptor(new DiscoveryInterceptor(appProperties, securityProperties));
    server.registerInterceptor(new AuthInterceptor(appProperties, securityProperties));
    server.registerInterceptor(new SearchInterceptor(appProperties, securityProperties));

    // Browsers send the bypass header on cross-origin requests, so CORS preflight must allow it.
    String bypassHeader = securityProperties.getBypassHeader();
    if (bypassHeader != null && !bypassHeader.isEmpty()) {
      corsInterceptor.ifPresent(cors -> cors.getConfig().addAllowedHeader(bypassHeader));
    }
  }
}
