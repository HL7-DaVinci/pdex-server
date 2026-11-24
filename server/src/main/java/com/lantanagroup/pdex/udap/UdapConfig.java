package com.lantanagroup.pdex.udap;

import com.lantanagroup.pdex.auth.AuthResourceProperties;
import com.lantanagroup.pdex.auth.FhirBaseUrl;
import com.lantanagroup.pdex.auth.IssuerMetadata;
import com.lantanagroup.pdex.auth.TrustAllSslContext;
import com.lantanagroup.pdex.auth.WellKnownInterceptor;
import ca.uhn.fhir.rest.server.RestfulServer;
import jakarta.annotation.PostConstruct;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Registers the UDAP metadata of GET {baseURL}/.well-known/udap on the FHIR server when app.auth.enabled and
 * app.auth.udap.enabled are true. When both are true, it also publishes the loaded RSA key and its certificate
 * chain, leaf first, as the bean udapCertificate.
 */
@Component
public class UdapConfig {

	private static final Logger log = LoggerFactory.getLogger(UdapConfig.class);

	/** The file name of a fetched certificate in the working directory. */
	static final String SAVED_CERTIFICATE = "generated-cert.pfx";

	/** The password of a fetched certificate when app.auth.udap.cert-password is absent. */
	static final String FETCH_PASSWORD = "udap-test";

	private final RestfulServer restfulServer;
	private final UdapProperties properties;
	private final AuthResourceProperties authProperties;
	private final IssuerMetadata issuerMetadata;
	private final Environment environment;
	private UdapCertificate signingCertificate;

	/** The folder of the saved certificate. A test sets a temporary folder. */
	Path savedCertificateDirectory = Path.of("");

	public UdapConfig(
			RestfulServer restfulServer,
			UdapProperties properties,
			AuthResourceProperties authProperties,
			IssuerMetadata issuerMetadata,
			Environment environment) {
		this.restfulServer = restfulServer;
		this.properties = properties;
		this.authProperties = authProperties;
		this.issuerMetadata = issuerMetadata;
		this.environment = environment;
	}

	@PostConstruct
	public void register() {
		if (!authProperties.isEnabled() || !properties.isEnabled()) {
			return;
		}
		String baseUrl = FhirBaseUrl.resolve(environment);
		LoadedCertificate loaded = certificate(baseUrl);
		signingCertificate = loaded.certificate();
		String problem = certificateProblem(loaded.certificate().chain().get(0), baseUrl);
		if (problem != null) {
			String remedy = StringUtils.isBlank(properties.getCertFile())
					? " Delete the file, and the next start fetches a new certificate from app.auth.issuer."
					: "";
			log.warn("{} {}, so a UDAP client rejects the signed metadata of {}.{}",
					loaded.source(), problem, baseUrl, remedy);
		}
		UdapMetadata metadata;
		try {
			metadata = new UdapMetadata(
					properties, baseUrl, loaded.certificate(), () -> issuerMetadata.wellKnown("udap"));
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException(
					loaded.source() + " has a key that cannot sign the UDAP metadata. " + e.getMessage(), e);
		}
		restfulServer.registerInterceptor(new WellKnownInterceptor("/.well-known/udap", metadata::build));
	}

	@Bean("udapCertificate")
	@ConditionalOnProperty(name = {"app.auth.enabled", "app.auth.udap.enabled"}, havingValue = "true")
	KeyStore.PrivateKeyEntry udapCertificate() {
		return new KeyStore.PrivateKeyEntry(
				signingCertificate.key(), signingCertificate.chain().toArray(new X509Certificate[0]));
	}

	/** Returns why a UDAP client rejects the metadata that the leaf certificate signs for the base URL, or null. */
	static String certificateProblem(X509Certificate leaf, String baseUrl) {
		try {
			Collection<List<?>> names = leaf.getSubjectAlternativeNames();
			if (names == null || !names.contains(List.of(6, baseUrl))) {
				return "has no URI alternative name that equals the base URL";
			}
			leaf.checkValidity();
			return null;
		} catch (CertificateException e) {
			return "is not valid (" + e.getMessage() + ")";
		}
	}

	/** With sslVerify false, the client accepts any certificate and any host name through TrustAllSslContext. */
	static HttpClient fetchClient(boolean sslVerify) {
		HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
		if (!sslVerify) {
			builder.sslContext(TrustAllSslContext.create());
		}
		return builder.build();
	}

	/** A loaded certificate and the key or file that gives it, for the startup messages. */
	private record LoadedCertificate(UdapCertificate certificate, String source) {}

	private LoadedCertificate certificate(String baseUrl) {
		String password = properties.getCertPassword();
		if (StringUtils.isNotBlank(properties.getCertFile())) {
			if (password == null) {
				throw new IllegalStateException("app.auth.udap.cert-file is set and app.auth.udap.cert-password is not. "
						+ "Set app.auth.udap.cert-password or the environment variable APP_AUTH_UDAP_CERTPASSWORD.");
			}
			return new LoadedCertificate(
					UdapCertificate.load(Path.of(properties.getCertFile()), password), "app.auth.udap.cert-file");
		}
		if (!properties.isFetchCert()) {
			throw new IllegalStateException("app.auth.udap.enabled is true, app.auth.udap.cert-file is not set, "
					+ "and app.auth.udap.fetch-cert is false");
		}
		String fetchPassword = Objects.requireNonNullElse(password, FETCH_PASSWORD);
		Path saved = savedCertificateDirectory.resolve(SAVED_CERTIFICATE).toAbsolutePath();
		if (!Files.exists(saved)) {
			try {
				new UdapCertificateFetcher(
								fetchClient(issuerMetadata.sslVerify()),
								authProperties.getIssuer(),
								properties.getFetchCertRetryAttempts(),
								properties.getFetchCertRetryDelay())
						.fetch(baseUrl, fetchPassword, saved);
			} catch (IllegalArgumentException e) {
				throw new IllegalStateException(
						"app.auth.issuer is not an absolute http or https URL. " + e.getMessage(), e);
			}
		}
		try {
			return new LoadedCertificate(UdapCertificate.load(saved, fetchPassword), "The saved certificate " + saved);
		} catch (IllegalStateException e) {
			throw new IllegalStateException("The saved certificate " + saved + " does not load. Delete the file, and "
					+ "the next start fetches a certificate from app.auth.issuer. " + e.getMessage(), e);
		}
	}
}
