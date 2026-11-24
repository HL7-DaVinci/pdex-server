package com.lantanagroup.pdex.scopes;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Scope check settings under the "app.auth.scopes" prefix in application.yaml. */
@Configuration
@ConfigurationProperties(prefix = "app.auth.scopes")
public class ScopeProperties {

	/** Turns the scope check on when app.auth.enabled is also true. Absent means false. */
	private boolean enabled;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}
}
