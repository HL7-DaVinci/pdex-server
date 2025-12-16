package com.lantanagroup.pdex.security;

import java.io.IOException;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ca.uhn.fhir.jpa.starter.AppProperties;
import org.springframework.util.ResourceUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;

@Interceptor
public class DiscoveryInterceptor {

  private final UdapProperties udapProperties;
  private final AppProperties appProperties;

  public DiscoveryInterceptor(AppProperties appProperties, SecurityProperties securityProperties) {
    this.udapProperties = securityProperties.getUdap();
    this.appProperties = appProperties;
  }

  @Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_PROCESSED)
  public boolean incomingRequestPreProcessed(HttpServletRequest theRequest, HttpServletResponse theResponse) throws UnrecoverableKeyException, KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException {

    String udapEndPoint = "/fhir/.well-known/udap";

    if (!theRequest.getRequestURI().equals(udapEndPoint)) {
      return true;
    }

    String securityServerBase = udapProperties.getSecurityServerBase();
    String authEndpoint = securityServerBase + "/connect/authorize";
    String tokenEndpoint = securityServerBase + "/connect/token";
    String userinfoEndpoint = securityServerBase + "/connect/userinfo";
    String revocationEndpoint = securityServerBase + "/connect/revocation";
    String registrationEndpoint = securityServerBase + "/connect/register";
    String certFile = udapProperties.getCertFile();
    String certPass = udapProperties.getCertPass();
    String fhirBase = appProperties.getServer_address();

    // Load certificate and keys
    FileInputStream stream = new FileInputStream(ResourceUtils.getFile(certFile));
    KeyStore ks = KeyStore.getInstance("pkcs12");
    ks.load(stream, certPass.toCharArray());
    String alias = ks.aliases().nextElement();

    X509Certificate certificate = (X509Certificate) ks.getCertificate(alias);
    RSAPublicKey publicKey = (RSAPublicKey) certificate.getPublicKey();
    RSAPrivateKey privateKey = (RSAPrivateKey) ks.getKey(alias, certPass.toCharArray());

    // Create signed metadata
    Algorithm algorithm = Algorithm.RSA256(publicKey, privateKey);
    String signedMetadata = JWT.create()
            .withHeader(Map.of(
                    "alg", algorithm.getName(),
                    "x5c", new String[] { Base64.getEncoder().encodeToString(certificate.getEncoded()) }))
            .withIssuer(fhirBase)
            .withSubject(fhirBase)
            .withIssuedAt(Date.from(Instant.now()))
            .withExpiresAt(Date.from(Instant.now().plusMillis(86400000)))
            .withJWTId(UUID.randomUUID().toString())
            .withClaim("authorization_endpoint", authEndpoint)
            .withClaim("token_endpoint", tokenEndpoint)
            .withClaim("registration_endpoint", registrationEndpoint)
            .sign(algorithm);

    // Build response JSON
    Gson gson = new Gson();
    JsonObject discoveryResponse = new JsonObject();
    discoveryResponse.add("udap_versions_supported", gson.toJsonTree(List.of("1")));
    discoveryResponse.add("udap_profiles_supported", gson.toJsonTree(List.of("udap_dcr", "udap_authn", "udap_authz")));
    discoveryResponse.add("udap_authorization_extensions_supported", gson.toJsonTree(List.of("hl7-b2b")));
    discoveryResponse.add("udap_authorization_extensions_required", gson.toJsonTree(List.of("hl7-b2b")));
    discoveryResponse.add("udap_certifications_supported", gson.toJsonTree(List.of("https://www.example.com/udap/profiles/example-certification")));
    discoveryResponse.add("udap_certifications_required", gson.toJsonTree(List.of("https://www.example.com/udap/profiles/example-certification")));
    discoveryResponse.add("grant_types_supported", gson.toJsonTree(List.of("authorization_code", "refresh_token", "client_credentials")));
    discoveryResponse.add("scopes_supported", gson.toJsonTree(List.of("openid", "patient/*.read", "patient/*.rs", "user/*.read", "user/*.rs", "system/*.read", "system/*.rs")));
    discoveryResponse.addProperty("authorization_endpoint", authEndpoint);
    discoveryResponse.addProperty("token_endpoint", tokenEndpoint);
    discoveryResponse.addProperty("userinfo_endpoint", userinfoEndpoint);
    discoveryResponse.addProperty("revocation_endpoint", revocationEndpoint);
    discoveryResponse.add("token_endpoint_auth_methods_supported", gson.toJsonTree(List.of("private_key_jwt")));
    discoveryResponse.add("token_endpoint_auth_signing_alg_values_supported", gson.toJsonTree(List.of("ES256", "ES384", "RS256", "RS384")));
    discoveryResponse.addProperty("registration_endpoint", registrationEndpoint);
    discoveryResponse.add("registration_endpoint_jwt_signing_alg_values_supported", gson.toJsonTree(List.of("ES256", "ES384", "RS256", "RS384")));
    discoveryResponse.addProperty("signed_metadata", signedMetadata);
    theResponse.setContentType("application/json");
    try {
      theResponse.getWriter().write(discoveryResponse.toString());
      theResponse.setStatus(200);
      theResponse.getWriter().flush();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return false;
  }
}
