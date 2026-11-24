package com.lantanagroup.pdex.scopes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SmartScopeTest {

	@ParameterizedTest(name = "{0}")
	@CsvSource({
		"patient/Observation.rs, patient, Observation, rs",
		"user/*.read, user, *, rs",
		"system/Patient.write, system, Patient, cud",
		"patient/Patient.*, patient, Patient, cruds",
		"system/Patient.cruds, system, Patient, cruds"
	})
	void parsesResourceScopes(String claim, String prefix, String type, String permissions) {
		assertEquals(List.of(new SmartScope(prefix, type, permissions)), SmartScope.parse(claim));
	}

	@Test
	void ignoresEachOtherEntry() {
		assertEquals(
				List.of(),
				SmartScope.parse("openid fhirUser launch/patient offline_access udap patient/Observation.rs?category=x"
						+ " patient/Observation.sr other/Patient.r patient/Patient. patient/Patient.x"));
		assertEquals(
				List.of(new SmartScope("system", "Patient", "r"), new SmartScope("system", "Observation", "s")),
				SmartScope.parse(List.of("system/Patient.r", "system/Observation.s")));
		assertEquals(List.of(), SmartScope.parse(null));
		assertEquals(List.of(), SmartScope.parse(42));
	}
}
