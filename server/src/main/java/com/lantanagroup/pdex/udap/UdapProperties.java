package com.lantanagroup.pdex.udap;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * UDAP metadata settings under the "app.auth.udap" prefix in application.yaml. A set endpoint replaces the value from
 * the UDAP metadata of the issuer. A UDAP list in the configuration replaces the list from the UDAP metadata of the
 * issuer, also when it is empty, and an absent UDAP list is null.
 */
@Configuration
@ConfigurationProperties(prefix = "app.auth.udap")
public class UdapProperties {

	/** Turns the UDAP metadata on when app.auth.enabled is also true. Absent means false. */
	private boolean enabled;

	private String authorizationEndpoint;

	private String tokenEndpoint;

	private String registrationEndpoint;

	/** A list with an entry replaces the scope list from the UDAP metadata of the issuer. */
	private List<String> scopesSupported = new ArrayList<>();

	private List<String> profilesSupported;

	private List<String> grantTypesSupported;

	private List<String> authorizationExtensionsSupported;

	private List<String> authorizationExtensionsRequired;

	private List<String> certificationsSupported;

	private List<String> certificationsRequired;

	private List<String> tokenEndpointAuthSigningAlgValuesSupported;

	private List<String> registrationEndpointJwtSigningAlgValuesSupported;

	/** Path of a PKCS#12 file with the signing key and its certificate chain. */
	private String certFile;

	/** Password of the certificate file. The environment variable APP_AUTH_UDAP_CERTPASSWORD also sets it. */
	private String certPassword;

	/**
	 * With no cert-file, uses the saved certificate in the working directory or gets one from
	 * {app.auth.issuer}/api/cert/generate. Absent means false.
	 */
	private boolean fetchCert;

	private int fetchCertRetryAttempts = 10;

	/** Milliseconds between two fetch tries. */
	private long fetchCertRetryDelay = 5000;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getAuthorizationEndpoint() {
		return authorizationEndpoint;
	}

	public void setAuthorizationEndpoint(String authorizationEndpoint) {
		this.authorizationEndpoint = authorizationEndpoint;
	}

	public String getTokenEndpoint() {
		return tokenEndpoint;
	}

	public void setTokenEndpoint(String tokenEndpoint) {
		this.tokenEndpoint = tokenEndpoint;
	}

	public String getRegistrationEndpoint() {
		return registrationEndpoint;
	}

	public void setRegistrationEndpoint(String registrationEndpoint) {
		this.registrationEndpoint = registrationEndpoint;
	}

	public List<String> getScopesSupported() {
		return scopesSupported;
	}

	public void setScopesSupported(List<String> scopesSupported) {
		this.scopesSupported = scopesSupported;
	}

	public List<String> getProfilesSupported() {
		return profilesSupported;
	}

	public void setProfilesSupported(List<String> profilesSupported) {
		this.profilesSupported = profilesSupported;
	}

	public List<String> getGrantTypesSupported() {
		return grantTypesSupported;
	}

	public void setGrantTypesSupported(List<String> grantTypesSupported) {
		this.grantTypesSupported = grantTypesSupported;
	}

	public List<String> getAuthorizationExtensionsSupported() {
		return authorizationExtensionsSupported;
	}

	public void setAuthorizationExtensionsSupported(List<String> authorizationExtensionsSupported) {
		this.authorizationExtensionsSupported = authorizationExtensionsSupported;
	}

	public List<String> getAuthorizationExtensionsRequired() {
		return authorizationExtensionsRequired;
	}

	public void setAuthorizationExtensionsRequired(List<String> authorizationExtensionsRequired) {
		this.authorizationExtensionsRequired = authorizationExtensionsRequired;
	}

	public List<String> getCertificationsSupported() {
		return certificationsSupported;
	}

	public void setCertificationsSupported(List<String> certificationsSupported) {
		this.certificationsSupported = certificationsSupported;
	}

	public List<String> getCertificationsRequired() {
		return certificationsRequired;
	}

	public void setCertificationsRequired(List<String> certificationsRequired) {
		this.certificationsRequired = certificationsRequired;
	}

	public List<String> getTokenEndpointAuthSigningAlgValuesSupported() {
		return tokenEndpointAuthSigningAlgValuesSupported;
	}

	public void setTokenEndpointAuthSigningAlgValuesSupported(List<String> tokenEndpointAuthSigningAlgValuesSupported) {
		this.tokenEndpointAuthSigningAlgValuesSupported = tokenEndpointAuthSigningAlgValuesSupported;
	}

	public List<String> getRegistrationEndpointJwtSigningAlgValuesSupported() {
		return registrationEndpointJwtSigningAlgValuesSupported;
	}

	public void setRegistrationEndpointJwtSigningAlgValuesSupported(
			List<String> registrationEndpointJwtSigningAlgValuesSupported) {
		this.registrationEndpointJwtSigningAlgValuesSupported = registrationEndpointJwtSigningAlgValuesSupported;
	}

	public String getCertFile() {
		return certFile;
	}

	public void setCertFile(String certFile) {
		this.certFile = certFile;
	}

	public String getCertPassword() {
		return certPassword;
	}

	public void setCertPassword(String certPassword) {
		this.certPassword = certPassword;
	}

	public boolean isFetchCert() {
		return fetchCert;
	}

	public void setFetchCert(boolean fetchCert) {
		this.fetchCert = fetchCert;
	}

	public int getFetchCertRetryAttempts() {
		return fetchCertRetryAttempts;
	}

	public void setFetchCertRetryAttempts(int fetchCertRetryAttempts) {
		this.fetchCertRetryAttempts = fetchCertRetryAttempts;
	}

	public long getFetchCertRetryDelay() {
		return fetchCertRetryDelay;
	}

	public void setFetchCertRetryDelay(long fetchCertRetryDelay) {
		this.fetchCertRetryDelay = fetchCertRetryDelay;
	}
}
