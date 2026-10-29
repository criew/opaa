package io.opaa.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds one request per operation that is well-formed by the specification, so that what answers
 * is the authorization and not the request validation: every required parameter and every required
 * body property gets its first enum value, its example (never an address) or a value its schema
 * admits. An id comes from the caller's {@code ids} by its name, a person's under any {@code
 * ...UserId}, {@code ownerId} or {@code subjectId}; any other id is {@link #UNKNOWN}.
 */
final class ProbeRequests {

  /** An id no fixture uses. */
  static final UUID UNKNOWN = UUID.fromString("5d1b6a4e-3f2c-4e8b-9a71-0c6d2e8f4b13");

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int MAX_DEPTH = 6;

  /**
   * Bodies the schema alone cannot make valid, because a rule of the domain refuses them before the
   * authorization is asked; {@code {name}} stands for the id of that name.
   */
  private static final Map<String, String> BODIES =
      Map.of(
          "previewPermissionTransfer",
          """
          {"sourceType": "USER", "sourceId": "{sourceId}", "targetType": "USER",
           "targetId": "{targetId}", "scope": ["OWNERSHIP"]}""",
          "executePermissionTransfer",
          """
          {"sourceType": "USER", "sourceId": "{sourceId}", "targetType": "USER",
           "targetId": "{targetId}", "scope": ["OWNERSHIP"], "confirmed": true,
           "previewId": "probe"}""",
          "createLibrary",
          """
          {"name": "Probe-Bibliothek", "sourceType": "UPLOAD"}""",
          "browseSource",
          """
          {"sourceUrl": "https://wiki.example.org", "libraryId": "{libraryId}"}""",
          "startConnectionAuthorization",
          """
          {"profileId": "{oauthProfileId}", "purpose": "LIBRARY_RECONNECT",
           "libraryId": "{libraryId}", "serviceAccountConfirmed": true}""",
          "upsertAssetGrant",
          """
          {"subjectType": "USER", "subjectId": "{targetId}", "role": "VIEWER"}""");

  /**
   * Path parameters whose example would let a rule of the domain refuse the request before the
   * authorization is asked: a source type with a listing, so the library's MANAGER bar decides.
   */
  private static final Map<String, Map<String, String>> PATH_VALUES =
      Map.of("browseSource", Map.of("sourceType", "CONFLUENCE"));

  private final ApiOperations api;
  private final Map<String, String> ids;
  private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

  ProbeRequests(ApiOperations api, Map<String, String> ids) {
    this.api = api;
    this.ids = ids;
  }

  AbstractMockHttpServletRequestBuilder<?> build(ApiOperations.Operation operation) {
    String path = operation.path();
    for (ApiOperations.Parameter parameter : operation.in("path")) {
      String value =
          PATH_VALUES
              .getOrDefault(operation.id(), Map.of())
              .getOrDefault(parameter.name(), value(parameter.name(), parameter.schema()));
      path = path.replace("{" + parameter.name() + "}", value);
    }
    HttpMethod method = HttpMethod.valueOf(operation.method());
    Map<String, Object> body =
        operation.bodySchema() == null ? null : api.resolve(operation.bodySchema());
    AbstractMockHttpServletRequestBuilder<?> request;
    if ("multipart/form-data".equals(operation.mediaType())) {
      MockMultipartHttpServletRequestBuilder multipart =
          MockMvcRequestBuilders.multipart(method, path);
      for (Map.Entry<String, Object> property : properties(body).entrySet()) {
        Map<String, Object> schema = api.resolve(ApiOperations.map(property.getValue()));
        if ("binary".equals(schema.get("format"))) {
          multipart.file(
              new MockMultipartFile(
                  property.getKey(),
                  "probe.txt",
                  MediaType.TEXT_PLAIN_VALUE,
                  "probe".getBytes(StandardCharsets.UTF_8)));
        } else if (required(body).contains(property.getKey())) {
          multipart.param(property.getKey(), value(property.getKey(), schema));
        }
      }
      request = multipart;
    } else {
      request = MockMvcRequestBuilders.request(method, path);
      if (operation.mediaType() == null) {
        request.contentType(MediaType.APPLICATION_JSON);
      } else if (operation.mediaType().startsWith("audio/")) {
        request
            .contentType("audio/webm")
            .content(new byte[] {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3});
      } else if (operation.mediaType().equals(MediaType.APPLICATION_FORM_URLENCODED_VALUE)) {
        request.contentType(MediaType.APPLICATION_FORM_URLENCODED);
        for (String name : required(body)) {
          Map<String, Object> schema = api.resolve(ApiOperations.map(properties(body).get(name)));
          request.param(name, value(name, schema));
        }
      } else {
        request
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                BODIES.containsKey(operation.id())
                    ? filled(BODIES.get(operation.id()))
                    : JSON.writeValueAsString(sample(body, null, 0)));
      }
    }
    for (ApiOperations.Parameter parameter : operation.in("query")) {
      if (parameter.required() || ids.containsKey(parameter.name())) {
        request.queryParam(parameter.name(), value(parameter.name(), parameter.schema()));
      }
    }
    for (ApiOperations.Parameter parameter : operation.in("header")) {
      if (parameter.required()) {
        request.header(parameter.name(), value(parameter.name(), parameter.schema()));
      }
    }
    return request;
  }

  private String filled(String template) {
    String body = template;
    for (Map.Entry<String, String> id : ids.entrySet()) {
      body = body.replace("{" + id.getKey() + "}", id.getValue());
    }
    return body.replaceAll("\\{[a-zA-Z]+Id}", UNKNOWN.toString());
  }

  /** A value for a parameter or a scalar property. */
  private String value(String name, Map<String, Object> schema) {
    String id = idFor(name);
    if (id != null) {
      return id;
    }
    Object sample = scalar(schema);
    return String.valueOf(sample);
  }

  private String idFor(String name) {
    if (name == null) {
      return null;
    }
    if (ids.containsKey(name)) {
      return ids.get(name);
    }
    if (name.endsWith("UserId") || name.equals("ownerId") || name.equals("subjectId")) {
      return ids.get("userId");
    }
    if (name.endsWith("Ids")) {
      return ids.get(name.substring(0, name.length() - 1));
    }
    return null;
  }

  private Object sample(Map<String, Object> declared, String name, int depth) {
    Map<String, Object> schema = api.resolve(declared);
    List<?> alternatives =
        !ApiOperations.list(schema.get("oneOf")).isEmpty()
            ? ApiOperations.list(schema.get("oneOf"))
            : ApiOperations.list(schema.get("anyOf"));
    if (!alternatives.isEmpty()) {
      return sample(ApiOperations.map(alternatives.getFirst()), name, depth);
    }
    String type = String.valueOf(schema.get("type"));
    if (type.equals("array")) {
      List<Object> items = new ArrayList<>();
      int count = schema.get("minItems") instanceof Number minimum ? minimum.intValue() : 0;
      if (count == 0 && namesAFixture(name, schema)) {
        count = 1;
      }
      String singular =
          name != null && name.endsWith("s") ? name.substring(0, name.length() - 1) : name;
      for (int i = 0; i < count && depth < MAX_DEPTH; i++) {
        items.add(sample(ApiOperations.map(schema.get("items")), singular, depth + 1));
      }
      return items;
    }
    if (type.equals("object") || schema.containsKey("properties") || schema.containsKey("allOf")) {
      Map<String, Object> object = new LinkedHashMap<>();
      if (depth >= MAX_DEPTH) {
        return object;
      }
      Map<String, Object> properties = properties(schema);
      List<String> required = required(schema);
      for (Map.Entry<String, Object> property : properties.entrySet()) {
        String key = property.getKey();
        if (required.contains(key) || namesAFixture(key, ApiOperations.map(property.getValue()))) {
          object.put(key, sample(ApiOperations.map(property.getValue()), key, depth + 1));
        }
      }
      return object;
    }
    String id = "uuid".equals(schema.get("format")) ? idFor(name) : null;
    return id != null ? id : scalar(schema);
  }

  /** Whether an optional property carries an id of the fixture, alone or as a list. */
  private boolean namesAFixture(String name, Map<String, Object> declared) {
    Map<String, Object> schema = api.resolve(declared);
    if ("array".equals(schema.get("type"))) {
      schema = api.resolve(ApiOperations.map(schema.get("items")));
    }
    return "uuid".equals(schema.get("format")) && idFor(name) != null;
  }

  private Object scalar(Map<String, Object> schema) {
    List<?> values = ApiOperations.list(schema.get("enum"));
    if (!values.isEmpty()) {
      return values.getFirst();
    }
    String type = String.valueOf(schema.get("type"));
    String format = String.valueOf(schema.get("format"));
    Object example = schema.get("example");
    if (!format.equals("uuid")
        && (example instanceof Number
            || example instanceof String text && !text.isBlank() && !text.contains("://"))) {
      return example;
    }
    if (type.equals("integer") || type.equals("number")) {
      if (schema.get("minimum") instanceof Number minimum) {
        return type.equals("integer") ? minimum.longValue() : minimum.doubleValue();
      }
      return schema.get("maximum") instanceof Number maximum && maximum.longValue() < 1
          ? maximum.longValue()
          : 1;
    }
    if (type.equals("boolean")) {
      return Boolean.TRUE.equals(schema.get("example"));
    }
    return switch (format) {
      case "uuid" -> UNKNOWN.toString();
      case "date-time" -> now.toString();
      case "date" -> LocalDate.ofInstant(now, ZoneOffset.UTC).toString();
      case "email" -> "probe@example.com";
      case "uri" -> "https://probe.invalid/";
      case "password" -> "Probe-Passwort-1234567890";
      default -> patterned(schema);
    };
  }

  /** A string its pattern and length bounds admit. */
  private String patterned(Map<String, Object> schema) {
    String pattern = String.valueOf(schema.get("pattern"));
    String text;
    if (pattern.contains("#[0-9A-Fa-f]")) {
      text = "#336699";
    } else if (pattern.contains("[0-9]{4}-")) {
      text = LocalDate.ofInstant(now, ZoneOffset.UTC).toString();
    } else if (pattern.startsWith("^[A-Z]")) {
      text = "PROBE";
    } else {
      text = "probe";
    }
    if (schema.get("minLength") instanceof Number minimum) {
      text = text + "x".repeat(Math.max(0, minimum.intValue() - text.length()));
    }
    if (schema.get("maxLength") instanceof Number maximum && text.length() > maximum.intValue()) {
      text = text.substring(0, maximum.intValue());
    }
    return text;
  }

  /** The properties of {@code schema}, those of its {@code allOf} parts included. */
  private Map<String, Object> properties(Map<String, Object> schema) {
    Map<String, Object> properties = new LinkedHashMap<>();
    if (schema == null) {
      return properties;
    }
    for (Object part : ApiOperations.list(schema.get("allOf"))) {
      properties.putAll(properties(api.resolve(ApiOperations.map(part))));
    }
    properties.putAll(ApiOperations.map(schema.get("properties")));
    return properties;
  }

  private List<String> required(Map<String, Object> schema) {
    List<String> required = new ArrayList<>();
    if (schema == null) {
      return required;
    }
    for (Object part : ApiOperations.list(schema.get("allOf"))) {
      required.addAll(required(api.resolve(ApiOperations.map(part))));
    }
    for (Object name : ApiOperations.list(schema.get("required"))) {
      required.add(String.valueOf(name));
    }
    return required;
  }
}
