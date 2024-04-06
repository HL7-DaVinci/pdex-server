package com.lantanagroup.pdex.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

@Configuration
@ConfigurationProperties(prefix = "security")
public class SecurityProperties {
  
  @Getter @Setter
	Boolean enableAuth;

  @Getter @Setter
  String bypassHeader;

  @Getter @Setter
	String clientId;
	@Getter @Setter
	String clientSecret;

  @Getter @Setter
  String issuer;
  @Getter @Setter
  String authorizationUrl;
  @Getter @Setter
  String introspectionUrl;
  @Getter @Setter
  String tokenUrl;

}
