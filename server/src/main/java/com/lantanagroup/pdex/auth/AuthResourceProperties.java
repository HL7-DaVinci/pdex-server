package com.lantanagroup.pdex.auth;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Bearer token check settings under the "app.auth" prefix in application.yaml.
 */
@Configuration
@ConfigurationProperties(prefix = "app.auth")
public class AuthResourceProperties {

	/** Turns the bearer token check on. Absent means false. */
	private boolean enabled;

	/** The issuer URL, which is the iss claim of an accepted token. */
	private String issuer;

	/** The aud claim that a token must hold. Absent means no audience check. */
	private String audience;

	/** The key set URL. Absent means the jwks_uri of the OpenID Connect discovery document of the issuer. */
	private String jwksUri;

	private List<String> publicEndpoints = new ArrayList<>();

	private String bypassHeader;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getIssuer() {
		return issuer;
	}

	public void setIssuer(String issuer) {
		this.issuer = issuer;
	}

	public String getAudience() {
		return audience;
	}

	public void setAudience(String audience) {
		this.audience = audience;
	}

	public String getJwksUri() {
		return jwksUri;
	}

	public void setJwksUri(String jwksUri) {
		this.jwksUri = jwksUri;
	}

	public List<String> getPublicEndpoints() {
		return publicEndpoints;
	}

	public void setPublicEndpoints(List<String> publicEndpoints) {
		this.publicEndpoints = publicEndpoints;
	}

	public String getBypassHeader() {
		return bypassHeader;
	}

	public void setBypassHeader(String bypassHeader) {
		this.bypassHeader = bypassHeader;
	}
}
