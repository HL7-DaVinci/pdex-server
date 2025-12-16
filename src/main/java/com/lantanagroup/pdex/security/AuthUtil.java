package com.lantanagroup.pdex.security;

import java.io.IOException;
import java.net.URL;
import java.security.interfaces.RSAPublicKey;
import java.util.List;

import com.auth0.jwt.exceptions.JWTVerificationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkException;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.UrlJwkProvider;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;

public class AuthUtil {

  public static boolean bypassAuth(RequestDetails theRequestDetails, SecurityProperties securityProperties) {

    // No check needed if authentication is disabled
    if (!securityProperties.getEnableAuth()) {
      return true;
    }

    // Bypass auth check if the request contains a configured bypass header
    if (securityProperties.getBypassHeader() != null && !securityProperties.getBypassHeader().isBlank() 
      && theRequestDetails.getHeader(securityProperties.getBypassHeader()) != null) {
      return true;
    }

    // Bypass auth for public endpoints
    List<String> publicEndpoints = List.of("/fhir/metadata", "/fhir/.well-known/udap");
    String requestPath = theRequestDetails.getCompleteUrl();
    if (publicEndpoints.stream().anyMatch(requestPath::endsWith)) {
      return true;
    }

    return false;
  }


  public static DecodedJWT getToken(RequestDetails theRequestDetails, SecurityProperties securityProperties) {

    String authHeader = theRequestDetails.getHeader(Constants.HEADER_AUTHORIZATION);
    if (authHeader == null || authHeader.isEmpty()
            || !authHeader.startsWith(Constants.HEADER_AUTHORIZATION_VALPREFIX_BEARER)) {
      throw new AuthenticationException("Missing or invalid Authorization header");
    }

    // Get token from header
    String token = authHeader.substring(Constants.HEADER_AUTHORIZATION_VALPREFIX_BEARER.length()).trim();
    DecodedJWT jwt = null;

    try {
      // configured as confidential client
      if (
        securityProperties.getClientId() != null && !securityProperties.getClientId().isBlank()
        && securityProperties.getClientSecret() != null && !securityProperties.getClientSecret().isBlank()
        && securityProperties.getIntrospectionUrl() != null && !securityProperties.getIntrospectionUrl().isBlank()
        ) {
        jwt = introspectionCheck(token, securityProperties);
      }
      // configured as public client
      else {
        jwt = validateToken(token, securityProperties);
      }
    } catch (Exception e) {
      throw new AuthenticationException("Error parsing token: " + e.getMessage());
    }

    return jwt;
  }


  public static DecodedJWT introspectionCheck(String token, SecurityProperties securityProperties) throws JsonMappingException, JsonProcessingException {

    // validate the token against the introspection endpoint
    RestTemplate restTemplate = new RestTemplate();
    HttpHeaders headers = new HttpHeaders();
    
    MultiValueMap<String, String> requestBody = new LinkedMultiValueMap<>();
    requestBody.add("token", token);
    requestBody.add("client_id", securityProperties.getClientId());
    requestBody.add("client_secret", securityProperties.getClientSecret());

    HttpEntity<MultiValueMap<String, String>> idpRequest = new HttpEntity<>(requestBody, headers);
    ResponseEntity<String> idpResponse = restTemplate.postForEntity(securityProperties.getIntrospectionUrl(), idpRequest, String.class);

    if (idpResponse.getStatusCode() == HttpStatus.OK) {
      ObjectMapper mapper = new ObjectMapper();
      JsonNode introspectionResponse = mapper.readTree(idpResponse.getBody());
      if (introspectionResponse.get("active").asBoolean()) {
        return JWT.decode(token);
      }
    }

    return null;
  }


  public static DecodedJWT validateToken(String token, SecurityProperties securityProperties) throws JwkException, IOException, InterruptedException {

    DecodedJWT decodedJWT = JWT.decode(token);
    String issuer = securityProperties.getIssuer();

    // Validate issuer
    if (!decodedJWT.getIssuer().equals(issuer)) {
      throw new JWTVerificationException(
              "Invalid issuer: Expected \"" + issuer + "\" but received \"" + decodedJWT.getIssuer() + "\"");
    }

    // Get JWKS URI from OIDC discovery
    /*HttpClient client = HttpClient.newBuilder().build();
    HttpRequest request = HttpRequest.newBuilder()
      .uri(URI.create(StringUtils.removeEnd(securityProperties.getIssuer(), "/") + "/.well-known/openid-configuration"))
      .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    String jwksUri = new ObjectMapper().readTree(response.body()).get("jwks_uri").asText();*/

    // Get public key from JWKS endpoint
    String jwksUri = securityProperties.getIssuer() + "/.well-known/openid-configuration/jwks";
    JwkProvider jwkProvider = new UrlJwkProvider(new URL(jwksUri));
    Jwk jwk = jwkProvider.get(decodedJWT.getKeyId());

    RSAPublicKey rsaPublicKey = (RSAPublicKey) jwk.getPublicKey();

    if (rsaPublicKey == null) {
      throw new JWTVerificationException("Could not determine public key");
    }

    // Verify token
    Algorithm algorithm = Algorithm.RSA256(rsaPublicKey, null);
    JWTVerifier verifier = JWT.require(algorithm)
            .withIssuer(issuer)
            .build();
    DecodedJWT verifiedJwt;
    try {
      verifiedJwt = verifier.verify(token);
    } catch (JWTVerificationException e) {
      throw new AuthenticationException("Token verification failed: " + e.getMessage());
    }
    return verifiedJwt;

  }

}
