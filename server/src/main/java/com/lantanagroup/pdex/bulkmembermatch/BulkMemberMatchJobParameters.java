package com.lantanagroup.pdex.bulkmembermatch;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonProperty;

import ca.uhn.fhir.model.api.IModelJson;
import lombok.Getter;
import lombok.Setter;

public class BulkMemberMatchJobParameters implements IModelJson {

  public static final String SPEC_STU2_1 = "2.1";
  public static final String SPEC_STU2_2 = "2.2";

  /** Raw JSON of the request Parameters resource submitted to $bulk-member-match */
  @Getter @Setter
  @JsonProperty
  private String requestParameters;

  /** PDex spec version the response must conform to: {@link #SPEC_STU2_1} (default) or {@link #SPEC_STU2_2} */
  @Getter @Setter
  @JsonProperty
  private String specVersion = SPEC_STU2_1;

  @Getter @Setter
  @JsonProperty
  private String originalRequestUrl;

  @Getter @Setter
  @JsonProperty
  private Date requestDate;

}
