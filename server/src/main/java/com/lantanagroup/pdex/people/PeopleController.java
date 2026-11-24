package com.lantanagroup.pdex.people;

import com.lantanagroup.pdex.login.LoginFilter;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.SortSpec;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.util.FhirTerser;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lists the Practitioner and Patient resources of this server for the person picker of the sign-in page. The read
 * runs in process with a SystemRequestDetails, so no token check applies to it. No exception leaves the method,
 * because the error dispatch of the container lets the frontend error view replace the response.
 */
@RestController
public class PeopleController {

	private static final Logger log = LoggerFactory.getLogger(PeopleController.class);

	private static final int LIMIT = 100;

	private static final MediaType PLAIN_TEXT = new MediaType("text", "plain", StandardCharsets.UTF_8);

	private final DaoRegistry daoRegistry;
	private final FhirTerser terser;
	private final boolean enabled;

	public PeopleController(
			DaoRegistry daoRegistry,
			FhirContext fhirContext,
			@Value("${app.login.people.enabled:false}") boolean enabled) {
		this.daoRegistry = daoRegistry;
		this.terser = fhirContext.newTerser();
		this.enabled = enabled;
	}

	@GetMapping("/auth/people")
	public ResponseEntity<Object> people(HttpServletRequest request) {
		if (!enabled) {
			return refuse(404, "The people list is not enabled");
		}
		if (!LoginFilter.hasProof(request)) {
			return refuse(403, "The request does not show that the app sent it");
		}
		try {
			return ResponseEntity.ok(new People(read("Practitioner"), read("Patient")));
		} catch (RuntimeException e) {
			log.warn("The people list could not be read: {}", e.getClass().getName());
			return refuse(500, "The people of this server could not be read");
		}
	}

	private List<Person> read(String type) {
		if (!daoRegistry.isResourceTypeSupported(type)) {
			return List.of();
		}
		SearchParameterMap map = new SearchParameterMap();
		map.setSort(new SortSpec("family"));
		map.setCount(LIMIT);
		map.setLoadSynchronousUpTo(LIMIT);
		return daoRegistry.getResourceDao(type).search(map, new SystemRequestDetails()).getResources(0, LIMIT)
				.stream()
				.map(resource -> new Person(resource.getIdElement().getIdPart(), name(resource)))
				.toList();
	}

	/** The text of the first name, else its given names and its family name, else the id. A blank part counts as absent. */
	private String name(IBaseResource resource) {
		String id = resource.getIdElement().getIdPart();
		List<IBase> names = terser.getValues(resource, "name");
		if (names.isEmpty()) {
			return id;
		}
		IBase first = names.get(0);
		String text = terser.getSinglePrimitiveValueOrNull(first, "text");
		if (text != null && !text.isBlank()) {
			return text;
		}
		String parts = Stream.concat(
						terser.getValues(first, "given").stream()
								.map(given -> ((IPrimitiveType<?>) given).getValueAsString()),
						Stream.of(terser.getSinglePrimitiveValueOrNull(first, "family")))
				.filter(part -> part != null && !part.isBlank())
				.collect(Collectors.joining(" "));
		return parts.isEmpty() ? id : parts;
	}

	private static ResponseEntity<Object> refuse(int status, String reason) {
		return ResponseEntity.status(status).contentType(PLAIN_TEXT).body(reason);
	}

	record People(List<Person> practitioners, List<Person> patients) {}

	record Person(String id, String name) {}
}
