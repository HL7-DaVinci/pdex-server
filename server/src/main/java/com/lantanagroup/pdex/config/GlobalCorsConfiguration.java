package com.lantanagroup.pdex.config;

import ca.uhn.fhir.jpa.starter.AppProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Global CORS configuration that applies to all endpoints in the application.
 * This configuration reuses the CORS settings from hapi.fhir.cors properties,
 * applying the same configuration that the HAPI FHIR CorsInterceptor uses.
 */
@Configuration
public class GlobalCorsConfiguration implements WebMvcConfigurer {

  private final AppProperties appProperties;

  public GlobalCorsConfiguration(AppProperties appProperties) {
    this.appProperties = appProperties;
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    if (appProperties.getCors() == null) {
      return;
    }

    // The proxy path permits no other origin. Spring uses the first mapping
    // that matches, so this mapping stays before the mapping for all paths.
    registry.addMapping("/proxy").allowedOrigins();

    // Apply CORS configuration to all paths
    var corsConfig = registry.addMapping("/**");

    // Add allowed origins from hapi.fhir.cors configuration
    List<String> allowedOrigins = appProperties.getCors().getAllowed_origin();
    if (allowedOrigins != null && !allowedOrigins.isEmpty()) {
      corsConfig.allowedOriginPatterns(allowedOrigins.toArray(new String[0]));
    }

    // Set allow credentials from hapi.fhir.cors configuration
    Boolean allowCredentials = appProperties.getCors().getAllow_Credentials();
    if (allowCredentials != null && allowCredentials) {
      corsConfig.allowCredentials(true);
    }

    // Configure headers - matching what StarterJpaConfig uses
    corsConfig.allowedHeaders(
      HttpHeaders.ORIGIN,
      HttpHeaders.ACCEPT,
      HttpHeaders.CONTENT_TYPE,
      HttpHeaders.AUTHORIZATION,
      HttpHeaders.CACHE_CONTROL,
      "x-fhir-starter",
      "X-Requested-With",
      "Prefer");

    // Configure exposed headers - matching what StarterJpaConfig uses
    corsConfig.exposedHeaders("Location", "Content-Location");

    // Configure HTTP methods - matching what StarterJpaConfig uses
    corsConfig.allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH", "HEAD");

    // Set max age for preflight requests
    corsConfig.maxAge(3600L);
  }
}
