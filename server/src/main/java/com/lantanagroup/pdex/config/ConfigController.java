package com.lantanagroup.pdex.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConfigController {

  private final String script;

  public ConfigController(
      FhirServers fhirServers,
      @Value("${app.proxy.enabled:false}") boolean proxyEnabled,
      @Value("${app.proxy.passthrough:false}") boolean passthrough,
      @Value("${app.login.enabled:false}") boolean loginEnabled,
      @Value("${app.login.people.enabled:false}") boolean peopleEnabled) throws JsonProcessingException {
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("fhirServers", fhirServers.entries());
    if (proxyEnabled) {
      config.put("proxy", Map.of("passthrough", passthrough));
    }
    if (loginEnabled) {
      config.put("login", peopleEnabled ? Map.of("people", true) : Map.of());
    }
    this.script = "window.APP_CONFIG = " + new JsonMapper().writeValueAsString(config) + ";";
  }

  @GetMapping(value = "/config.js", produces = "application/javascript")
  public String getConfig() {
    return script;
  }
}
