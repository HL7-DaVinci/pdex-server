package com.lantanagroup.pdex.scopes;

import com.lantanagroup.pdex.auth.BearerTokenInterceptor;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizedList;
import ca.uhn.fhir.rest.server.interceptor.auth.SearchNarrowingInterceptor;
import java.util.List;
import java.util.Map;

/**
 * Narrows a type search of a token with a patient scope to the Patient compartment of the patient claim. A type that
 * a user or system scope with the letter s covers gets no narrowing, and the entries of a Bundle get none.
 */
public class PatientSearchNarrowingInterceptor extends SearchNarrowingInterceptor {

	@Override
	protected AuthorizedList buildAuthorizedList(RequestDetails details) {
		// HAPI also narrows the GET entries of a Bundle and changes a read entry of a type in the Patient compartment
		// into a search, so a Bundle gets no narrowing.
		if (details.getRestOperationType() == RestOperationTypeEnum.TRANSACTION) {
			return null;
		}
		if (!(details.getUserData().get(BearerTokenInterceptor.CLAIMS_KEY) instanceof Map<?, ?> claims)
				|| !(claims.get("patient") instanceof String patient)
				|| patient.isBlank()) {
			return null;
		}
		List<SmartScope> scopes = SmartScope.parse(claims.get("scope"));
		if (scopes.stream().noneMatch(scope -> "patient".equals(scope.prefix()))
				|| ScopeAuthorizationInterceptor.searchable(scopes, details.getResourceName())) {
			return null;
		}
		return new AuthorizedList().addCompartment("Patient/" + patient);
	}
}
