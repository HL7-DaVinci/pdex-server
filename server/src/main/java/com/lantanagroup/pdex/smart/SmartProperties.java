package com.lantanagroup.pdex.smart;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** SMART configuration settings under the "app.auth.smart" prefix in application.yaml. */
@Configuration
@ConfigurationProperties(prefix = "app.auth.smart")
public class SmartProperties {

	/** Turns the SMART configuration on when app.auth.enabled is also true. Absent means false. */
	private boolean enabled;

	/** The SMART capabilities that the document publishes, as written. */
	private List<String> capabilities = new ArrayList<>();

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public List<String> getCapabilities() {
		return capabilities;
	}

	public void setCapabilities(List<String> capabilities) {
		this.capabilities = capabilities;
	}
}
