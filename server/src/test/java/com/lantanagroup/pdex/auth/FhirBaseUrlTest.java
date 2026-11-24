package com.lantanagroup.pdex.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.env.MockEnvironment;

class FhirBaseUrlTest {

	static Stream<Arguments> environments() {
		return Stream.of(
				Arguments.of(Map.of("hapi.fhir.server_address", "http://localhost:8080/fhir/"), "http://localhost:8080/fhir"),
				Arguments.of(
						Map.of("server.port", "9090", "server.servlet.context-path", "/x/"), "http://localhost:9090/x/fhir"),
				Arguments.of(Map.of(), "http://localhost:8080/fhir"));
	}

	@ParameterizedTest(name = "{0} gives {1}")
	@MethodSource("environments")
	void resolvesTheBaseUrl(Map<String, String> properties, String baseUrl) {
		MockEnvironment environment = new MockEnvironment();
		properties.forEach(environment::setProperty);
		assertEquals(baseUrl, FhirBaseUrl.resolve(environment));
	}
}
