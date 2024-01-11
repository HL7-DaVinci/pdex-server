package ca.uhn.fhir.jpa.starter.cdshooks;

import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceRequestJson;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties({"extension"})
public class CdsHooksRequest extends CdsServiceRequestJson {}
