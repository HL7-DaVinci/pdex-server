package com.lantanagroup.pdex.cdshooks;


import java.lang.reflect.Method;

import ca.uhn.hapi.fhir.cdshooks.svc.CdsHooksContextBooter;
import ca.uhn.hapi.fhir.cdshooks.svc.CdsServiceCache;
import ca.uhn.fhir.context.ConfigurationException;
import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.hapi.fhir.cdshooks.api.CdsService;
import ca.uhn.hapi.fhir.cdshooks.api.CdsServiceFeedback;
import ca.uhn.hapi.fhir.cdshooks.api.CdsServicePrefetch;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceJson;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class PdexCdsHooksContextBooter extends CdsHooksContextBooter {
	private static final Logger ourLog = LoggerFactory.getLogger(CdsHooksContextBooter.class);
	private final CdsServiceCache pdexCdsServiceCache = new CdsServiceCache();
	private List<Object> pdexCdsServiceBeans = new ArrayList<>();
	private Class<?> pdexDefinitionsClass;
	private AnnotationConfigApplicationContext pdexAppCtx;
	private static final String PDEX_CDS_SERVICES_BEAN_NAME = "cdsServices";
	
	public PdexCdsHooksContextBooter() {
		super();
		PdexServerAppCtx ctx = new PdexServerAppCtx();
		pdexCdsServiceBeans = ctx.cdsServices();
	}
	
	public void setDefinitionsClass(Class<?> theDefinitionsClass) {
		pdexDefinitionsClass = theDefinitionsClass;
	}

	public CdsServiceCache buildCdsServiceCache() {
		
		for (Object serviceBean : pdexCdsServiceBeans) {
			myExtractCdsServices(serviceBean);
		}
		return pdexCdsServiceCache;
	}

	private void myExtractCdsServices(Object theServiceBean) {
		Method[] methods = theServiceBean.getClass().getMethods();
		// Sort alphabetically so service list output is deterministic (to ensure GET /cds-services is idempotent).
		// This also simplifies testing :-)
		List<Method> sortedMethods = Arrays.stream(methods)
				.sorted(Comparator.comparing(Method::getName))
				.collect(Collectors.toList());
		for (Method method : sortedMethods) {
			if (method.isAnnotationPresent(CdsService.class)) {
				CdsService annotation = method.getAnnotation(CdsService.class);
				CdsServiceJson cdsServiceJson = new CdsServiceJson();
				cdsServiceJson.setId(annotation.value());
				cdsServiceJson.setHook(annotation.hook());
				cdsServiceJson.setDescription(annotation.description());
				cdsServiceJson.setTitle(annotation.title());
				cdsServiceJson.setExtension(myValidateJson(annotation.extension()));
				for (CdsServicePrefetch prefetch : annotation.prefetch()) {
					cdsServiceJson.addPrefetch(prefetch.value(), prefetch.query());
					cdsServiceJson.addSource(prefetch.value(), prefetch.source());
				}
				pdexCdsServiceCache.registerService(
						cdsServiceJson.getId(),
						theServiceBean,
						method,
						cdsServiceJson,
						annotation.allowAutoFhirClientPrefetch());
			}
			if (method.isAnnotationPresent(CdsServiceFeedback.class)) {
				CdsServiceFeedback annotation = method.getAnnotation(CdsServiceFeedback.class);
				pdexCdsServiceCache.registerFeedback(annotation.value(), theServiceBean, method);
			}
		}
	}

	String myValidateJson(String theExtension) {
		if (StringUtils.isEmpty(theExtension)) {
			return null;
		}
		try {
			final ObjectMapper mapper = new ObjectMapper();
			mapper.readTree(theExtension);
			return theExtension;
		} catch (JsonProcessingException e) {
			final String message = String.format("Invalid JSON: %s", e.getMessage());
			ourLog.debug(message);
			throw new UnprocessableEntityException(Msg.code(2378) + message);
		}
	}
	
	public void start() {
		if (pdexDefinitionsClass == null) {
			ourLog.info("No application context defined");
			return;
		}
		ourLog.info("Starting Spring ApplicationContext for class: {}", pdexDefinitionsClass);

		pdexAppCtx = new AnnotationConfigApplicationContext();
		pdexAppCtx.register(pdexDefinitionsClass);
		pdexAppCtx.refresh();

		try {
			if (pdexAppCtx.containsBean(PDEX_CDS_SERVICES_BEAN_NAME)) {
				pdexCdsServiceBeans = (List<Object>) pdexAppCtx.getBean(PDEX_CDS_SERVICES_BEAN_NAME, List.class);
			} else {
				ourLog.info("Context has no bean named {}", PDEX_CDS_SERVICES_BEAN_NAME);
			}

			if (pdexCdsServiceBeans.isEmpty()) {
				throw new ConfigurationException(Msg.code(2379)
						+ "No CDS Services found in the context (need bean called " + PDEX_CDS_SERVICES_BEAN_NAME + ")");
			}

		} catch (ConfigurationException e) {
			stop();
			throw e;
		} catch (Exception e) {
			stop();
			throw new ConfigurationException(Msg.code(2393) + e.getMessage(), e);
		}
	}
	
}
