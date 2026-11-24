package com.lantanagroup.pdex.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The FHIR servers that app.fhir.servers lists. The property is a YAML list of
 * name and url entries, or one string that holds a JSON array of such entries.
 * Each url is an absolute http or https URL, or a root-relative path on the
 * origin of the page. A value that is not valid stops the application at startup.
 */
@Component
public class FhirServers {

  private static final String PROPERTY = "app.fhir.servers";
  private static final String NOT_AN_ARRAY = PROPERTY + " is not a JSON array of name and url entries";

  // The string form also accepts JavaScript object syntax: names with no
  // quotes, single quotes, and a comma after the last entry.
  private static final JsonMapper LENIENT_JSON = JsonMapper.builder()
      .enable(
          JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES,
          JsonReadFeature.ALLOW_SINGLE_QUOTES,
          JsonReadFeature.ALLOW_TRAILING_COMMA)
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .build();

  public record Entry(String name, String url) {}

  private final List<Entry> entries;

  public FhirServers(Environment environment) {
    // A YAML list gives no value for the plain key, so a value here is the string form.
    String value = environment.getProperty(PROPERTY);
    List<Entry> read = value == null ? bind(environment) : parse(value);
    for (int i = 0; i < read.size(); i++) {
      validate(i, read.get(i));
    }
    this.entries = List.copyOf(read);
  }

  public List<Entry> entries() {
    return entries;
  }

  private static List<Entry> parse(String value) {
    if (!StringUtils.hasText(value)) {
      return List.of();
    }
    List<Entry> parsed;
    try {
      parsed = LENIENT_JSON.readValue(value, new TypeReference<List<Entry>>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(NOT_AN_ARRAY, e);
    }
    if (parsed == null) {
      throw new IllegalStateException(NOT_AN_ARRAY);
    }
    return parsed;
  }

  private static List<Entry> bind(Environment environment) {
    try {
      return Binder.get(environment).bind(PROPERTY, Bindable.listOf(Entry.class)).orElseGet(List::of);
    } catch (BindException e) {
      throw new IllegalStateException(PROPERTY + " is not a list of name and url entries", e);
    }
  }

  private static void validate(int index, Entry entry) {
    String location = PROPERTY + "[" + index + "]";
    if (entry == null || !StringUtils.hasText(entry.name()) || !StringUtils.hasText(entry.url())) {
      throw new IllegalStateException(location + " must have a name and a url");
    }
    // The message leaves out the value, because user information in a URL can be a password.
    if (!isServerUrl(entry.url())) {
      throw new IllegalStateException(location + ".url must be an absolute http or https URL"
          + " with a host and no user information, query, or fragment, or a path that starts with"
          + " one / followed by at least one more character and has no query, fragment, or backslash");
    }
  }

  private static boolean isServerUrl(String url) {
    // The browser resolves a root-relative path against the page. A path that starts with //
    // names a different host. The frontend joins a resource path to the base with a /, so a
    // base of / alone gives //metadata, which also names a different host.
    if (url.startsWith("/")) {
      return url.length() > 1 && !url.startsWith("//")
          && url.indexOf('?') < 0 && url.indexOf('#') < 0 && url.indexOf('\\') < 0;
    }
    try {
      URI uri = new URI(url);
      return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
          && uri.getHost() != null
          && uri.getRawUserInfo() == null
          && uri.getRawQuery() == null
          && uri.getRawFragment() == null;
    } catch (URISyntaxException e) {
      return false;
    }
  }
}
