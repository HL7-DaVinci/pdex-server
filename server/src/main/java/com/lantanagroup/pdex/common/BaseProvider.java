package com.lantanagroup.pdex.common;

import ca.uhn.fhir.rest.server.RestfulServer;
import jakarta.annotation.PostConstruct;

/**
 * Base class for FHIR providers. A subclass passes the RestfulServer to this constructor and is
 * registered on it after construction.
 * 
 * Example usage:
 * <pre>
 * {@code @Component }
 * public class MyProvider extends BaseProvider {
 *   public MyProvider(RestfulServer restfulServer) {
 *     super(restfulServer);
 *   }
 *
 *   {@code @Operation(name = "$my-operation") }
 *   public OperationOutcome myOperation() {
 *     return new OperationOutcome();
 *   }
 * }
 * </pre>
 */
public abstract class BaseProvider {

  protected final RestfulServer restfulServer;

  protected BaseProvider(RestfulServer restfulServer) {
    this.restfulServer = restfulServer;
  }

  @PostConstruct
  public void registerProvider() {
    restfulServer.registerProvider(this);
  }

}
