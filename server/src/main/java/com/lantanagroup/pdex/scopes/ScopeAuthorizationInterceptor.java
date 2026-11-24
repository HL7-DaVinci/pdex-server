package com.lantanagroup.pdex.scopes;

import com.lantanagroup.pdex.auth.BearerTokenInterceptor;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizationInterceptor;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRule;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRuleBuilder;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRuleBuilderRuleOp;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRuleBuilderRuleOpClassifier;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRuleBuilderRuleOpClassifierFinished;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRuleTester;
import ca.uhn.fhir.rest.server.interceptor.auth.PolicyEnum;
import ca.uhn.fhir.rest.server.interceptor.auth.RuleBuilder;
import ca.uhn.fhir.util.OperationOutcomeUtil;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.hl7.fhir.instance.model.api.IBaseReference;
import org.hl7.fhir.instance.model.api.IIdType;

/**
 * Checks the SMART scopes of the verified token against the resource type and the action of each FHIR request. A
 * patient scope also limits the request to the Patient compartment of the patient claim. A request with no verified
 * claims passes with no check. A direct request for an operation passes only when allowsOperation accepts it.
 */
public class ScopeAuthorizationInterceptor extends AuthorizationInterceptor {

	private static final String DENIED = "The token does not allow this request";
	private static final Set<RestOperationTypeEnum> READ_OPERATIONS =
			EnumSet.of(RestOperationTypeEnum.READ, RestOperationTypeEnum.VREAD, RestOperationTypeEnum.HISTORY_INSTANCE);
	private static final Set<RestOperationTypeEnum> SEARCH_OPERATIONS = EnumSet.of(
			RestOperationTypeEnum.SEARCH_TYPE,
			RestOperationTypeEnum.SEARCH_SYSTEM,
			RestOperationTypeEnum.HISTORY_TYPE,
			RestOperationTypeEnum.HISTORY_SYSTEM,
			RestOperationTypeEnum.GET_PAGE);
	private static final Set<RestOperationTypeEnum> OPERATIONS = EnumSet.of(
			RestOperationTypeEnum.EXTENDED_OPERATION_SERVER,
			RestOperationTypeEnum.EXTENDED_OPERATION_TYPE,
			RestOperationTypeEnum.EXTENDED_OPERATION_INSTANCE);

	private final FhirContext context;

	public ScopeAuthorizationInterceptor(FhirContext context) {
		super(PolicyEnum.DENY);
		this.context = context;
	}

	/** Runs one time for each HTTP request. The entries of a transaction or a batch use the same list. */
	@Override
	public List<IAuthRule> buildRuleList(RequestDetails details) {
		if (!(details.getUserData().get(BearerTokenInterceptor.CLAIMS_KEY) instanceof Map<?, ?> verified)) {
			return new RuleBuilder().allowAll().build();
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> claims = (Map<String, Object>) verified;
		List<SmartScope> scopes = SmartScope.parse(claims.get("scope"));
		// The engine also checks the reads and writes of an operation against this list, so an accepted operation gets
		// the allow-all list.
		if (OPERATIONS.contains(details.getRestOperationType())) {
			return allowsOperation(details, scopes, claims)
					? new RuleBuilder().allowAll().build()
					: new RuleBuilder().denyAll().build();
		}
		Set<String> resourceTypes = details.getFhirContext().getResourceTypes();
		IAuthRuleBuilder rules = new RuleBuilder();
		Tester searches =
				new Tester(request -> conditionSearchable(request, scopes) && referencesSearchable(request, scopes));
		for (SmartScope scope : scopes) {
			if (!"*".equals(scope.type()) && !resourceTypes.contains(scope.type())) {
				continue;
			}
			// allow() with no name returns one shared builder, and its write() and create() share one rule builder.
			String letters = scope.permissions();
			if (letters.contains("r") || letters.contains("s")) {
				addRule(rules.allow(scope.toString()).read(), scope, claims, List.of(new ReadTester(letters)));
			}
			if (letters.contains("u")) {
				addRule(rules.allow(scope.toString()).write(), scope, claims,
						List.of(new Tester(request -> isUpdate(request)), searches));
			}
			if (letters.contains("c")) {
				addRule(rules.allow(scope.toString()).create(), scope, claims, withType(scope, searches));
			}
			if (letters.contains("d")) {
				addRule(rules.allow(scope.toString()).delete(), scope, claims, withType(scope, searches));
			}
		}
		return rules.allow().transaction().withAnyOperation().andApplyNormalRules()
				.andThen().allow().metadata()
				.andThen().allow().operation().withAnyName().atAnyLevel().andAllowAllResponses()
						.withTester(new Tester(request -> allowsOperation(request.requestDetails, scopes, claims)))
				.andThen().denyAll()
				.build();
	}

	/**
	 * Returns true when a scope of the token covers the operation. A scope covers an operation on its type, with any
	 * letters, and only the type * covers an operation at server level. A patient scope covers only an operation on
	 * the Patient instance of the patient claim. Change this method to check operations in a different way.
	 */
	protected boolean allowsOperation(RequestDetails details, List<SmartScope> scopes, Map<String, Object> claims) {
		String type = details.getResourceName();
		boolean ownPatient = "Patient".equals(type)
				&& details.getId() != null
				&& claims.get("patient") instanceof String patient
				&& patient.equals(details.getId().getIdPart());
		return scopes.stream()
				.filter(scope -> "*".equals(scope.type()) || scope.type().equals(type))
				.anyMatch(scope -> !"patient".equals(scope.prefix()) || ownPatient);
	}

	/**
	 * Returns the resources that one scope covers: each resource of the type for a user or system scope, and the
	 * Patient compartment of the patient claim for a patient scope. A null result grants nothing, as for a patient
	 * scope with no patient claim. Change this method to limit a scope in a different way.
	 */
	protected IAuthRuleBuilderRuleOpClassifierFinished limit(
			IAuthRuleBuilderRuleOpClassifier rule, SmartScope scope, Map<String, Object> claims) {
		if (!"patient".equals(scope.prefix())) {
			return rule.withAnyId();
		}
		if (!(claims.get("patient") instanceof String patient) || patient.isBlank()) {
			return null;
		}
		IIdType owner = context.getVersion().newIdType().setParts(null, "Patient", patient, null);
		// The one-owner form adds to an existing rule of the same operation, type, and compartment, and both testers apply.
		return rule.inCompartment("Patient", List.of(owner));
	}

	@Override
	protected void handleDeny(RequestDetails details, Verdict verdict) {
		ForbiddenOperationException exception = new ForbiddenOperationException(
				DENIED, OperationOutcomeUtil.createOperationOutcome("error", DENIED, "forbidden", details.getFhirContext(), null));
		exception.addResponseHeader("WWW-Authenticate", "Bearer error=\"insufficient_scope\"");
		throw exception;
	}

	private void addRule(
			IAuthRuleBuilderRuleOp operation,
			SmartScope scope,
			Map<String, Object> claims,
			List<IAuthRuleTester> testers) {
		IAuthRuleBuilderRuleOpClassifier rule =
				"*".equals(scope.type()) ? operation.allResources() : operation.resourcesOfType(scope.type());
		IAuthRuleBuilderRuleOpClassifierFinished limited = limit(rule, scope, claims);
		if (limited != null) {
			testers.forEach(limited::withTester);
		}
	}

	/** Puts a type tester before the tester when the scope has a type other than *. */
	private static List<IAuthRuleTester> withType(SmartScope scope, IAuthRuleTester tester) {
		return "*".equals(scope.type())
				? List.of(tester)
				: List.of(new Tester(request -> hasType(request, scope.type())), tester);
	}

	private static boolean isBundle(IAuthRuleTester.RuleTestRequest request) {
		return request.requestDetails.getRestOperationType() == RestOperationTypeEnum.TRANSACTION;
	}

	/**
	 * Accepts an update and a patch. It also accepts CREATE for an update request, because the engine checks an update
	 * that makes a new resource as CREATE before the write.
	 */
	private static boolean isUpdate(IAuthRuleTester.RuleTestRequest request) {
		return request.operation == RestOperationTypeEnum.UPDATE
				|| request.operation == RestOperationTypeEnum.PATCH
				|| (request.operation == RestOperationTypeEnum.CREATE
						&& request.requestDetails.getRestOperationType() == RestOperationTypeEnum.UPDATE);
	}

	/**
	 * Accepts a check only when its resource, or its id when it has no resource, has the type of the scope. The engine
	 * does not check the type of a created resource with no id under a compartment rule. At the check of the request,
	 * it also does not check the type of a conditional delete or of a delete by id under a compartment rule.
	 */
	private static boolean hasType(IAuthRuleTester.RuleTestRequest request, String type) {
		if (request.resource != null) {
			return type.equals(request.requestDetails.getFhirContext().getResourceType(request.resource));
		}
		return request.resourceId != null && type.equals(request.resourceId.getResourceType());
	}

	/**
	 * Accepts a check whose request has no conditional URL, or whose token can search the type that the condition
	 * searches, so the status does not tell whether a resource matches. A Bundle entry uses the fixed conditional URL,
	 * because getConditionalUrl throws at the storage check of a delete entry and reads the query string of the Bundle
	 * POST as a condition.
	 */
	private static boolean conditionSearchable(IAuthRuleTester.RuleTestRequest request, List<SmartScope> scopes) {
		RequestDetails details = request.requestDetails;
		boolean entry = details.getRestOperationType() == RestOperationTypeEnum.TRANSACTION;
		boolean create = request.operation == RestOperationTypeEnum.CREATE;
		// An entry URL with an id is not conditional, also when a query string such as ?_cascade=delete gives it a
		// conditional URL. A create entry is the exception, because HAPI ignores its URL and runs its ifNoneExist.
		if (entry && !create && request.resourceId != null && request.resourceId.hasIdPart()) {
			return true;
		}
		String condition =
				entry ? details.getFixedConditionalUrl() : details.getConditionalUrl(details.getRestOperationType());
		if (condition == null) {
			return true;
		}
		if (!entry) {
			return searchable(scopes, details.getResourceName());
		}
		// For a create entry, HAPI searches the type of the resource and ignores the URL.
		if (create) {
			return request.resource != null
					&& searchable(scopes, details.getFhirContext().getResourceType(request.resource));
		}
		// HAPI searches the type of the URL for an update entry, so the token needs s on both types when they differ. The
		// resource of a patch entry is the patch document.
		boolean resourceSearchable = request.resource == null
				|| request.operation == RestOperationTypeEnum.PATCH
				|| searchable(scopes, details.getFhirContext().getResourceType(request.resource));
		return request.resourceId != null
				&& searchable(scopes, request.resourceId.getResourceType())
				&& resourceSearchable;
	}

	/**
	 * Accepts a check whose resource has no reference in the form of a search, for example Patient?identifier=123, or
	 * whose token can search the type of each such reference. HAPI puts the id of the one match in place of such a
	 * reference, so the status of the write tells whether zero, one, or more resources match.
	 */
	private boolean referencesSearchable(IAuthRuleTester.RuleTestRequest request, List<SmartScope> scopes) {
		return request.resource == null
				|| context.newTerser().getAllPopulatedChildElementsOfType(request.resource, IBaseReference.class).stream()
						.map(reference -> reference.getReferenceElement().getValue())
						.filter(value -> value != null && value.contains("?"))
						.allMatch(value -> searchable(scopes, searchType(value)));
	}

	/** Returns the type of a reference in the form of a search as HAPI reads it: Patient of [base/]Patient[/]?query. */
	private static String searchType(String reference) {
		String path = reference.substring(0, reference.indexOf('?'));
		if (path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		return path.substring(path.lastIndexOf('/') + 1);
	}

	/**
	 * Returns true when a user or system scope of the token has the letter s on the type, so the token can search each
	 * resource of the type. A patient scope does not count, because the search narrowing does not apply to a condition
	 * or to a reference.
	 */
	static boolean searchable(List<SmartScope> scopes, String type) {
		return scopes.stream()
				.anyMatch(scope -> !"patient".equals(scope.prefix())
						&& scope.permissions().contains("s")
						&& ("*".equals(scope.type()) || scope.type().equals(type)));
	}

	/**
	 * Accepts the operations of the letters r and s. On a response, it also accepts READ in a transaction or a batch,
	 * because the engine checks each resource of a Bundle response one more time as READ.
	 */
	private static final class ReadTester implements IAuthRuleTester {

		private final Set<RestOperationTypeEnum> operations = EnumSet.noneOf(RestOperationTypeEnum.class);

		ReadTester(String letters) {
			if (letters.contains("r")) {
				operations.addAll(READ_OPERATIONS);
			}
			if (letters.contains("s")) {
				operations.addAll(SEARCH_OPERATIONS);
			}
		}

		@Override
		public boolean matches(RuleTestRequest request) {
			return operations.contains(request.operation);
		}

		@Override
		public boolean matchesOutput(RuleTestRequest request) {
			return operations.contains(request.operation)
					|| (request.operation == RestOperationTypeEnum.READ && isBundle(request));
		}
	}

	/**
	 * Applies one predicate to the check of the request and to the check of the response. The engine reads the user data
	 * of the request details before it applies a rule, so a tester always gets request details.
	 */
	private static final class Tester implements IAuthRuleTester {

		private final Predicate<RuleTestRequest> predicate;

		Tester(Predicate<RuleTestRequest> predicate) {
			this.predicate = predicate;
		}

		@Override
		public boolean matches(RuleTestRequest request) {
			return predicate.test(request);
		}

		@Override
		public boolean matchesOutput(RuleTestRequest request) {
			return predicate.test(request);
		}
	}
}
