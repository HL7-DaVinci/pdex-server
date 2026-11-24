package com.lantanagroup.pdex.auth;

import ca.uhn.fhir.rest.server.RestfulServer;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Registers the bearer token check on the FHIR server when app.auth.enabled is true. With app.auth.enabled true,
 * a blank app.auth.issuer stops the application at startup.
 */
@Component
public class AuthResourceConfig {

	private static final Logger log = LoggerFactory.getLogger(AuthResourceConfig.class);

	private final RestfulServer restfulServer;
	private final AuthResourceProperties properties;
	private final IssuerMetadata issuerMetadata;

	public AuthResourceConfig(
			RestfulServer restfulServer, AuthResourceProperties properties, IssuerMetadata issuerMetadata) {
		this.restfulServer = restfulServer;
		this.properties = properties;
		this.issuerMetadata = issuerMetadata;
	}

	@PostConstruct
	public void register() {
		if (!properties.isEnabled()) {
			return;
		}
		String bypassHeader = properties.getBypassHeader();
		if (StringUtils.isNotBlank(bypassHeader)) {
			log.warn("app.auth.bypass-header is set, so each FHIR request with the header {} skips the bearer token check",
					bypassHeader);
		}
		if (!issuerMetadata.sslVerify()) {
			log.warn("app.ssl-verify is false, so the auth features accept any certificate from the hosts they call");
		}
		restfulServer.registerInterceptor(new BearerTokenInterceptor(
				issuerMetadata,
				properties.getAudience(),
				properties.getJwksUri(),
				properties.getPublicEndpoints(),
				bypassHeader));
	}
}
