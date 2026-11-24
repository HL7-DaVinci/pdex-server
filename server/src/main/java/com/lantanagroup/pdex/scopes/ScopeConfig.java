package com.lantanagroup.pdex.scopes;

import com.lantanagroup.pdex.auth.AuthResourceProperties;
import ca.uhn.fhir.rest.server.RestfulServer;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Registers the scope check and the patient search narrowing on the FHIR server when app.auth.enabled and
 * app.auth.scopes.enabled are true.
 */
@Component
public class ScopeConfig {

	private final RestfulServer restfulServer;
	private final ScopeProperties properties;
	private final AuthResourceProperties authProperties;

	public ScopeConfig(
			RestfulServer restfulServer, ScopeProperties properties, AuthResourceProperties authProperties) {
		this.restfulServer = restfulServer;
		this.properties = properties;
		this.authProperties = authProperties;
	}

	@PostConstruct
	public void register() {
		if (!authProperties.isEnabled() || !properties.isEnabled()) {
			return;
		}
		restfulServer.registerInterceptor(new ScopeAuthorizationInterceptor(restfulServer.getFhirContext()));
		restfulServer.registerInterceptor(new PatientSearchNarrowingInterceptor());
	}
}
