package com.lantanagroup.pdex.cdshooks;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PdexServerAppCtx {

   /**
    * This bean is a list of CDS Hooks classes, each one
    * of which implements one or more CDS-Hook Services.
    */
  
   @Bean(name = "cdsServices")
   public List<Object> cdsServices(){
      List<Object> retVal = new ArrayList<>();
      retVal.add(new PdexAppointmentBookCdsService());
// add other CDS Hooks classes...
      return retVal;
   }
}