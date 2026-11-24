package com.lantanagroup.pdex.scopes;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One SMART resource scope of the form (patient|user|system)/(Type|*).(permissions). The permissions are the SMART
 * v2 letters in the order cruds. The v1 forms read, write, and * give rs, cud, and cruds.
 */
public record SmartScope(String prefix, String type, String permissions) {

	private static final Pattern RESOURCE_SCOPE =
			Pattern.compile("(patient|user|system)/([A-Z][A-Za-z]*|\\*)\\.(read|write|\\*|(?=[cruds])c?r?u?d?s?)");

	/**
	 * Returns the resource scopes of the scope claim. The claim is one string with white space between the scopes, or
	 * a list of such strings. Each other value gives an empty list. A scope in a different form gives no element, so a
	 * scope that the parser does not fully understand grants nothing.
	 */
	public static List<SmartScope> parse(Object scopeClaim) {
		Stream<?> values;
		if (scopeClaim instanceof String text) {
			values = Stream.of(text);
		} else if (scopeClaim instanceof Collection<?> list) {
			values = list.stream();
		} else {
			return List.of();
		}
		return values.filter(String.class::isInstance)
				.flatMap(text -> Arrays.stream(((String) text).split("\\s+")))
				.map(RESOURCE_SCOPE::matcher)
				.filter(Matcher::matches)
				.map(scope -> new SmartScope(scope.group(1), scope.group(2), letters(scope.group(3))))
				.toList();
	}

	private static String letters(String permissions) {
		return switch (permissions) {
			case "read" -> "rs";
			case "write" -> "cud";
			case "*" -> "cruds";
			default -> permissions;
		};
	}
}
