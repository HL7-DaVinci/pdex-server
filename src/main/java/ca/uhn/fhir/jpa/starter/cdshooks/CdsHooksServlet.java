package ca.uhn.fhir.jpa.starter.cdshooks;

import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.hapi.fhir.cdshooks.api.ICdsServiceRegistry;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceRequestJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServicesJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.lantanagroup.pdex.cdshooks.PdexCdsHooksRequest;

import org.apache.http.entity.ContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.beans.factory.annotation.Qualifier;

import java.io.IOException;
import java.util.stream.Collectors;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static ca.uhn.hapi.fhir.cdshooks.config.CdsHooksConfig.CDS_HOOKS_OBJECT_MAPPER_FACTORY;

@Configurable
public class CdsHooksServlet extends HttpServlet {
	private static final Logger logger = LoggerFactory.getLogger(CdsHooksServlet.class);
	private static final long serialVersionUID = 1L;

	@Autowired
	private AppProperties appProperties;

	@Autowired
	private ProviderConfiguration providerConfiguration;

	@Autowired
	ICdsServiceRegistry cdsServiceRegistry;

	@Autowired
	RestfulServer restfulServer;

	@Autowired
	@Qualifier(CDS_HOOKS_OBJECT_MAPPER_FACTORY)
	ObjectMapper objectMapper;

	protected ProviderConfiguration getProviderConfiguration() {
		return this.providerConfiguration;
	}

	// CORS Pre-flight
	@Override
	protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
		ErrorHandling.setAccessControlHeaders(resp, appProperties);
		resp.setHeader("Content-Type", ContentType.APPLICATION_JSON.getMimeType());
		resp.setHeader("X-Content-Type-Options", "nosniff");
		resp.setStatus(HttpServletResponse.SC_OK);
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		logger.info(request.getRequestURI());
		if (!request.getRequestURL().toString().endsWith("/cds-services")
				&& !request.getRequestURL().toString().endsWith("/cds-services/")) {
			logger.error(request.getRequestURI());
			throw new ServletException("This servlet is not configured to handle GET requests.");
		}
		ErrorHandling.setAccessControlHeaders(response, appProperties);
		response.setHeader("Content-Type", ContentType.APPLICATION_JSON.getMimeType());
		response.getWriter()
				.println(new GsonBuilder()
						.setPrettyPrinting()
						.create()
						.toJson(JsonParser.parseString(objectMapper.writeValueAsString(getServices()))));
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		try {
			if (request.getContentType() == null || !request.getContentType().startsWith("application/json")) {
				throw new ServletException(String.format(
						"Invalid content type %s. Please use application/json.", request.getContentType()));
			}
			logger.info(request.getRequestURI());
			String service = request.getPathInfo().replace("/", "");

			logger.info("Service: " + service);
			String requestJson = request.getReader().lines().collect(Collectors.joining());
			logger.info(requestJson);
			// Rick Geimer: replaced with custom PdexCdsHooksRequest, which allows extensions
			// CdsHooksRequest cdsHooksRequest = objectMapper.readValue(requestJson, CdsHooksRequest.class);
			PdexCdsHooksRequest cdsHooksRequest = objectMapper.readValue(requestJson, PdexCdsHooksRequest.class);
			logRequestInfo(cdsHooksRequest, requestJson);

			logger.info("Available services: " + cdsServiceRegistry.getCdsServicesJson());

			CdsServiceResponseJson serviceResponseJson = cdsServiceRegistry.callService(service, cdsHooksRequest);

			// Using GSON pretty print format as Jackson's is ugly
			String jsonResponse = new GsonBuilder()
					.disableHtmlEscaping()
					.setPrettyPrinting()
					.create()
					.toJson(JsonParser.parseString(objectMapper.writeValueAsString(serviceResponseJson)));
			logger.info(jsonResponse);
			response.setContentType("text/json;charset=UTF-8");
			response.getWriter().println(jsonResponse);
		} catch (BaseServerResponseException e) {
			ErrorHandling.handleError(response, "ERROR: Exception connecting to remote server.", e, appProperties);
			logger.error(e.toString());
			e.printStackTrace();
		} catch (Exception e) {
			logger.error(e.toString());
			e.printStackTrace();
			throw new ServletException("ERROR: Exception in cds-hooks processing.", e);
		}
	}

	private void logRequestInfo(CdsServiceRequestJson request, String jsonRequest) {
		logger.info(jsonRequest);
		logger.info("cds-hooks hook instance: {}", request.getHookInstance());
		logger.info("cds-hooks local server address: {}", appProperties.getServer_address());
		logger.info("cds-hooks fhir server address: {}", request.getFhirServer());
		logger.info(
				"cds-hooks cql_logging_enabled: {}",
				this.getProviderConfiguration().getCqlLoggingEnabled());
	}

	private CdsServicesJson getServices() {
		return cdsServiceRegistry.getCdsServicesJson();
	}
}
