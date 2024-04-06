package com.lantanagroup.pdex.security;

import lombok.Getter;
import lombok.Setter;

public class SmartConfigurationObject {

  @Getter @Setter
  String authorization_endpoint;

  @Getter @Setter
  String[] grant_types_supported;

  @Getter @Setter
  String token_endpoint;

  @Getter @Setter
  String[] code_challenge_methods_supported;
  
}
