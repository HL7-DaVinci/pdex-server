package com.lantanagroup.pdex.smart;

import com.lantanagroup.pdex.auth.AuthResourceProperties;
import com.lantanagroup.pdex.auth.IssuerMetadata;
import com.lantanagroup.pdex.auth.WellKnownInterceptor;
import ca.uhn.fhir.rest.server.RestfulServer;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Registers the SMART configuration of GET {baseURL}/.well-known/smart-configuration on the FHIR server when
 * app.auth.enabled and app.auth.smart.enabled are true.
 */
@Component
public class SmartConfig {

	private final RestfulServer restfulServer;
	private final SmartProperties properties;
	private final AuthResourceProperties authProperties;
	private final IssuerMetadata issuerMetadata;

	public SmartConfig(
			RestfulServer restfulServer,
			SmartProperties properties,
			AuthResourceProperties authProperties,
			IssuerMetadata issuerMetadata) {
		this.restfulServer = restfulServer;
		this.properties = properties;
		this.authProperties = authProperties;
		this.issuerMetadata = issuerMetadata;
	}

	@PostConstruct
	public void register() {
		if (!authProperties.isEnabled() || !properties.isEnabled()) {
			return;
		}
		SmartConfiguration configuration = new SmartConfiguration(
				properties.getCapabilities(), () -> issuerMetadata.wellKnown("openid-configuration"));
		restfulServer.registerInterceptor(
				new WellKnownInterceptor("/.well-known/smart-configuration", configuration::build));
	}
}
