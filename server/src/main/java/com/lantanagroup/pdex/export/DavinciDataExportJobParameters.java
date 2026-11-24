package com.lantanagroup.pdex.export;

import java.util.Date;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;

import ca.uhn.fhir.rest.api.server.bulk.BulkExportJobParameters;
import lombok.Getter;
import lombok.Setter;

public class DavinciDataExportJobParameters extends BulkExportJobParameters {
  
  @Getter @Setter
  @JsonProperty
  private Date until;

  @Getter @Setter
  @JsonProperty
  private String exportType;

  @Getter @Setter
  @JsonProperty
  private Set<String> originalTypeFilters;
    
}
