package com.lantanagroup.pdex.auth;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.springframework.core.env.Environment;

/** The base URL of the FHIR endpoint of this server. */
public final class FhirBaseUrl {

	private FhirBaseUrl() {}

	public static String resolve(Environment environment) {
		String serverAddress = environment.getProperty("hapi.fhir.server_address");
		if (StringUtils.isNotBlank(serverAddress)) {
			return Strings.CS.removeEnd(serverAddress, "/");
		}
		return "http://localhost:" + environment.getProperty("server.port", "8080")
				+ Strings.CS.removeEnd(environment.getProperty("server.servlet.context-path", ""), "/") + "/fhir";
	}
}
