package com.lantanagroup.pdex.security;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.interfaces.RSAPublicKey;

import org.apache.commons.lang3.StringUtils;
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

    return false;
  }


  public static DecodedJWT getToken(RequestDetails theRequestDetails, SecurityProperties securityProperties) {
    
    String authHeader = theRequestDetails.getHeader(Constants.HEADER_AUTHORIZATION);
    if (authHeader == null) {
      return null;
    }

    if (!authHeader.startsWith(Constants.HEADER_AUTHORIZATION_VALPREFIX_BEARER)) {
      throw new AuthenticationException("Expected Bearer token not found in Authorization header");
    }

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

    HttpClient client = HttpClient.newBuilder().build();
    HttpRequest request = HttpRequest.newBuilder()
      .uri(URI.create(StringUtils.removeEnd(securityProperties.getIssuer(), "/") + "/.well-known/openid-configuration"))
      .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    String jwksUri = new ObjectMapper().readTree(response.body()).get("jwks_uri").asText();

    JwkProvider provider = new UrlJwkProvider(new URL(jwksUri));
    Jwk jwk = provider.get(decodedJWT.getKeyId());

    RSAPublicKey rsaPublicKey = (RSAPublicKey) jwk.getPublicKey();

    Algorithm algorithm = Algorithm.RSA256(rsaPublicKey, null);
    JWTVerifier verifier = JWT.require(algorithm)
      .withIssuer(securityProperties.getIssuer())
      .build();

    return verifier.verify(token);

  }

}
