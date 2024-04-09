package com.lantanagroup.pdex.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.mvc.ProxyExchange;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("auth")
public class SmartAuthProxyController {

  Logger logger = LoggerFactory.getLogger(SmartAuthProxyController.class);

  @Autowired
  private SecurityProperties securityProperties;

  @CrossOrigin(origins = "*")
  @PostMapping("/token")
  public ResponseEntity<?> postToken(ProxyExchange<?> proxy) throws JsonMappingException, JsonProcessingException {
    ResponseEntity<?> response = proxy.uri(securityProperties.getTokenUrl()).post();

    ObjectMapper mapper = new ObjectMapper();
    String data = mapper.writeValueAsString(response.getBody());

    ObjectNode tokenResponse = (ObjectNode) mapper.readTree(data);

    DecodedJWT jwt = JWT.decode(tokenResponse.get("access_token").asText());
    Claim patientClaim = jwt.getClaim("patient");
    
    if (patientClaim != null && !patientClaim.isNull() && !patientClaim.isMissing()) {
      tokenResponse.put("patient", patientClaim.asString());
    }

    return ResponseEntity.ok(tokenResponse);
  }
  
}
