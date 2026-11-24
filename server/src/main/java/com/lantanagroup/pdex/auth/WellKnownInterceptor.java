package com.lantanagroup.pdex.auth;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Answers GET {baseURL}{path} with the JSON of one .well-known document and ends the request. The answer does not
 * depend on the query string. Each other request goes on to HAPI.
 */
@Interceptor
public class WellKnownInterceptor {

	private static final Logger log = LoggerFactory.getLogger(WellKnownInterceptor.class);
	private static final ObjectMapper JSON = new ObjectMapper();

	private final String path;
	private final Supplier<Map<String, Object>> document;

	/** The path is the path info of the request below the FHIR servlet, for example /.well-known/{name}. */
	public WellKnownInterceptor(String path, Supplier<Map<String, Object>> document) {
		this.path = path;
		this.document = document;
	}

	@Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_PROCESSED)
	public boolean serve(HttpServletRequest request, HttpServletResponse response) throws IOException {
		if (!"GET".equals(request.getMethod()) || !path.equals(request.getPathInfo())) {
			return true;
		}
		Map<String, Object> body;
		try {
			body = document.get();
		} catch (RuntimeException e) {
			log.error("Could not build the document of {}", path, e);
			response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			return false;
		}
		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType("application/json");
		JSON.writeValue(response.getOutputStream(), body);
		return false;
	}
}
