package com.lantanagroup.pdex.cdshooks;

import ca.uhn.hapi.fhir.cdshooks.api.CdsService;
import ca.uhn.hapi.fhir.cdshooks.api.CdsServiceFeedback;
import ca.uhn.hapi.fhir.cdshooks.api.CdsServicePrefetch;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceFeedbackJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceIndicatorEnum;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceRequestJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseCardJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseCardSourceJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsHooksExtension;
import java.io.PrintWriter;
import java.io.StringWriter;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.stereotype.Component;

@Component
public class PdexAppointmentBookCdsService { 
	
   @CdsService(value = "appointment-book",
      hook = "appointment-book",
      title = "Book an appointment",
      description = "This service books an appointment for the patient",
      prefetch = {
         @CdsServicePrefetch(value = "patient", query = "Patient/{{context.patientId}}"),
         @CdsServicePrefetch(value = "encounter", query = "Encounter/{{context.encounterId}}")
      })
   
   public CdsServiceResponseJson appointmentBook(CdsServiceRequestJson theCdsRequest) {
	  String patientId = (String) theCdsRequest.getContext().get("patientId");
      Patient patient = (Patient) theCdsRequest.getPrefetch("patient");
      Encounter encounter = (Encounter) theCdsRequest.getPrefetch("encounter");
      Bundle appts = (Bundle) theCdsRequest.getContext().getResource("appointments");
      CdsHooksExtension extension = theCdsRequest.getExtension();
      System.out.println(theCdsRequest.toString());
      CdsServiceResponseJson response = new CdsServiceResponseJson();
      CdsServiceResponseCardJson card = new CdsServiceResponseCardJson();
      StringWriter summary = new StringWriter();
      PrintWriter pw = new PrintWriter(summary);
      if (patient != null) pw.println("Hello " + patient.getNameFirstRep().getNameAsSingleString());
      else pw.println("Hello patientId " + patientId);
      if (extension != null) pw.println("Extension: " + extension);
      card.setSummary(summary.toString());
      card.setIndicator(CdsServiceIndicatorEnum.INFO);
      CdsServiceResponseCardSourceJson source = new CdsServiceResponseCardSourceJson();
      source.setLabel("PDex Server Reference Implementation");
      card.setSource(source);
      response.addCard(card);
      return response;
   }

   @CdsServiceFeedback("example-service")
   public String appointmentBookFeedback(CdsServiceFeedbackJson theFeedback) {
      return "{\"message\": \"Thank you for your feedback dated " + theFeedback.getOutcomeTimestamp() + "!\"}";
   }
}