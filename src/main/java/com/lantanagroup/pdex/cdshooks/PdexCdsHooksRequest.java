package com.lantanagroup.pdex.cdshooks;

import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceRequestJson;
import com.fasterxml.jackson.annotation.JsonProperty;

// @JsonIgnoreProperties({"extension"})
public class PdexCdsHooksRequest extends CdsServiceRequestJson {
	@JsonProperty(value = "extension", required = true)
	Object myExtension;
	
	public String getStringExtension() {
		return myExtension.toString();
	}

	public PdexCdsHooksRequest setExtension(Object theExtension) {
		this.myExtension = theExtension;
		return this;
	}
	
}
