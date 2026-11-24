package com.lantanagroup.pdex.people;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.SimpleBundleProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PeopleControllerTest {

	private static final FhirContext FHIR = FhirContext.forR4Cached();

	private final DaoRegistry daoRegistry = mock(DaoRegistry.class);

	@Test
	void listsPractitionersAndPatientsWithTheirNames() throws Exception {
		dao("Practitioner", resource("{\"resourceType\":\"Practitioner\",\"id\":\"pr-1\","
				+ "\"name\":[{\"text\":\"Dr. Jane Doe\",\"given\":[\"Jane\"],\"family\":\"Doe\"}]}"));
		IFhirResourceDao<?> patients = dao("Patient",
				resource("{\"resourceType\":\"Patient\",\"id\":\"pa-1\",\"meta\":{\"versionId\":\"3\"},"
						+ "\"name\":[{\"given\":[\"Sam\",\"Lee\"],\"family\":\"Roe\"}]}"),
				resource("{\"resourceType\":\"Patient\",\"id\":\"pa-2\"}"));

		ResponseEntity<Object> response = controller(true).people(appRequest());

		assertEquals(200, response.getStatusCode().value());
		assertEquals("{\"practitioners\":[{\"id\":\"pr-1\",\"name\":\"Dr. Jane Doe\"}],"
						+ "\"patients\":[{\"id\":\"pa-1\",\"name\":\"Sam Lee Roe\"},{\"id\":\"pa-2\",\"name\":\"pa-2\"}]}",
				new ObjectMapper().writeValueAsString(response.getBody()));
		ArgumentCaptor<SearchParameterMap> search = ArgumentCaptor.forClass(SearchParameterMap.class);
		verify(patients).search(search.capture(), any(RequestDetails.class));
		assertEquals(100, search.getValue().getCount());
		assertEquals("family", search.getValue().getSort().getParamName());
	}

	@Test
	void answers404WhenTheKeyIsOff() {
		assertEquals(404, controller(false).people(appRequest()).getStatusCode().value());
		verifyNoInteractions(daoRegistry);
	}

	private PeopleController controller(boolean enabled) {
		return new PeopleController(daoRegistry, FHIR, enabled);
	}

	private IFhirResourceDao<?> dao(String type, IBaseResource... resources) {
		IFhirResourceDao<?> dao = mock(IFhirResourceDao.class);
		when(daoRegistry.isResourceTypeSupported(type)).thenReturn(true);
		doReturn(dao).when(daoRegistry).getResourceDao(type);
		when(dao.search(any(SearchParameterMap.class), any(RequestDetails.class)))
				.thenReturn(new SimpleBundleProvider(List.of(resources)));
		return dao;
	}

	private static IBaseResource resource(String json) {
		return FHIR.newJsonParser().parseResource(json);
	}

	private static MockHttpServletRequest appRequest() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/people");
		request.addHeader("X-App-Request", "1");
		return request;
	}
}
