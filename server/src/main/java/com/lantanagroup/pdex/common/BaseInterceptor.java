package com.lantanagroup.pdex.common;

import ca.uhn.fhir.rest.server.RestfulServer;
import jakarta.annotation.PostConstruct;

/**
 * Base class for FHIR interceptors. A subclass passes the RestfulServer to this constructor and is
 * registered on it after construction.
 * 
 * Example usage:
 * <pre>
 * {@code @Component }
 * public class MyInterceptor extends BaseInterceptor {
 *   public MyInterceptor(RestfulServer restfulServer) {
 *     super(restfulServer);
 *   }
 *
 *   {@code @Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_UPDATED)}
 *   public void update(IBaseResource theOldResource, IBaseResource theResource) {
 *     // Custom logic here
 *   }
 * }
 * </pre>
 */
public abstract class BaseInterceptor {

  protected final RestfulServer restfulServer;

  protected BaseInterceptor(RestfulServer restfulServer) {
    this.restfulServer = restfulServer;
  }

  @PostConstruct
  public void registerInterceptor() {
    restfulServer.registerInterceptor(this);
  }

}
