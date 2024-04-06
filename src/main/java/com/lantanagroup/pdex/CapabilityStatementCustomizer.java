package com.lantanagroup.pdex;

import java.util.ArrayList;

import org.hl7.fhir.instance.model.api.IBaseConformance;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.UriType;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestSecurityComponent;

import com.lantanagroup.pdex.security.SecurityProperties;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.starter.AppProperties;

@Interceptor
public class CapabilityStatementCustomizer {

  AppProperties appProperties;
  SecurityProperties securityProperties;

  public CapabilityStatementCustomizer(AppProperties appProperties, SecurityProperties securityProperties) {
    this.appProperties = appProperties;
    this.securityProperties = securityProperties;
  }
  
  @Hook(Pointcut.SERVER_CAPABILITY_STATEMENT_GENERATED)
  public void customize(IBaseConformance theCapabilityStatement) {

    CapabilityStatement cs = (CapabilityStatement) theCapabilityStatement;

    cs.getSoftware().setName("PDex Server");

    // add support for older clients expecting security properties from STU1
    CodeableConcept service = new CodeableConcept();
    service.addCoding().setSystem("http://hl7.org/fhir/restful-security-service").setCode("SMART-on-FHIR");
    service.setText("OAuth2 using SMART-on-FHIR profile (see http://docs.smarthealthit.org/)");
    Extension oauthExtension = new Extension();
    ArrayList<Extension> uris = new ArrayList<Extension>();
    uris.add(new Extension("authorize", new UriType(securityProperties.getAuthorizationUrl())));
    uris.add(new Extension("introspect", new UriType(securityProperties.getIntrospectionUrl())));
    uris.add(new Extension("token", new UriType(securityProperties.getTokenUrl())));
    oauthExtension.setUrl("http://fhir-registry.smarthealthit.org/StructureDefinition/oauth-uris");
    oauthExtension.setExtension(uris);

    CapabilityStatementRestSecurityComponent security = new CapabilityStatementRestSecurityComponent();
    security.addService(service);
    security.addExtension(oauthExtension);
    cs.getRest().get(0).setSecurity(security);

  }

}
