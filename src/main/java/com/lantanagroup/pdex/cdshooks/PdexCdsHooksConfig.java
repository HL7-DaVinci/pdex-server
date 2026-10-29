package com.lantanagroup.pdex.cdshooks;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import ca.uhn.fhir.jpa.starter.cdshooks.CdsHooksConfigCondition;
import ca.uhn.fhir.jpa.starter.cr.CrConfigCondition;
import ca.uhn.hapi.fhir.cdshooks.svc.CdsHooksContextBooter;

@Configuration
@Conditional({CdsHooksConfigCondition.class, CrConfigCondition.class})
public class PdexCdsHooksConfig {

  /**
   * The starter registers an empty CdsHooksContextBooter. This primary bean replaces it and
   * boots the PDex CDS services from PdexServerAppCtx. HAPI never calls start() itself.
   */
  @Bean(destroyMethod = "stop")
  @Primary
  public CdsHooksContextBooter pdexCdsHooksContextBooter() {
    CdsHooksContextBooter booter = new CdsHooksContextBooter();
    booter.setDefinitionsClass(PdexServerAppCtx.class);
    booter.start();
    return booter;
  }
}
