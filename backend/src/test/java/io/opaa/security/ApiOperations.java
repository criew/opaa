package io.opaa.security;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * The operations of the bundled OpenAPI specification ({@code /openapi/opaa-api.yaml} on the class
 * path), read as plain maps: each with its parameters and the schema and media type of its request
 * body. The specification is the only list of endpoints; nothing here is maintained by hand.
 */
final class ApiOperations {

  static final String BUNDLED_SPECIFICATION = "/openapi/opaa-api.yaml";

  private static final List<String> METHODS = List.of("get", "post", "put", "patch", "delete");

  /** One parameter; {@code schema} is resolved through {@code $ref}. */
  record Parameter(String name, String in, boolean required, Map<String, Object> schema) {}

  /**
   * One operation. {@code mediaType} and {@code bodySchema} are {@code null} for an operation
   * without a request body; the first declared media type wins.
   */
  record Operation(
      String id,
      String method,
      String path,
      List<Parameter> parameters,
      String mediaType,
      Map<String, Object> bodySchema) {

    List<Parameter> in(String location) {
      return parameters.stream().filter(parameter -> parameter.in().equals(location)).toList();
    }

    String label() {
      return id + " (" + method + " " + path + ")";
    }
  }

  private final Map<String, Object> schemas;
  private final List<Operation> operations;

  private ApiOperations(Map<String, Object> specification) {
    Map<String, Object> components = map(specification.get("components"));
    this.schemas = map(components.get("schemas"));
    Map<String, Object> sharedParameters = map(components.get("parameters"));
    List<Operation> read = new ArrayList<>();
    for (Map.Entry<String, Object> path : map(specification.get("paths")).entrySet()) {
      Map<String, Object> item = map(path.getValue());
      for (String method : METHODS) {
        Map<String, Object> operation = map(item.get(method));
        if (operation.isEmpty()) {
          continue;
        }
        List<Parameter> parameters = new ArrayList<>();
        List<Object> declared = new ArrayList<>(list(item.get("parameters")));
        declared.addAll(list(operation.get("parameters")));
        for (Object entry : declared) {
          Map<String, Object> parameter = map(entry);
          if (parameter.containsKey("$ref")) {
            parameter = map(sharedParameters.get(lastSegment(parameter.get("$ref"))));
          }
          parameters.add(
              new Parameter(
                  (String) parameter.get("name"),
                  (String) parameter.get("in"),
                  Boolean.TRUE.equals(parameter.get("required")),
                  resolve(map(parameter.get("schema")))));
        }
        String mediaType = null;
        Map<String, Object> bodySchema = null;
        Map<String, Object> content = map(map(operation.get("requestBody")).get("content"));
        if (!content.isEmpty()) {
          Map.Entry<String, Object> first = content.entrySet().iterator().next();
          mediaType = first.getKey();
          bodySchema = map(map(first.getValue()).get("schema"));
        }
        read.add(
            new Operation(
                (String) operation.get("operationId"),
                method.toUpperCase(Locale.ROOT),
                path.getKey(),
                List.copyOf(parameters),
                mediaType,
                bodySchema));
      }
    }
    this.operations = List.copyOf(read);
  }

  static ApiOperations bundled() {
    try (InputStream in = ApiOperations.class.getResourceAsStream(BUNDLED_SPECIFICATION)) {
      if (in == null) {
        throw new IllegalStateException(BUNDLED_SPECIFICATION + " is not on the class path");
      }
      return new ApiOperations(new Yaml().load(in));
    } catch (IOException e) {
      throw new IllegalStateException("the bundled specification is not readable", e);
    }
  }

  List<Operation> operations() {
    return operations;
  }

  /** {@code schema} with every top-level {@code $ref} followed. */
  Map<String, Object> resolve(Map<String, Object> schema) {
    Map<String, Object> resolved = schema;
    for (int hop = 0; hop < 10 && resolved.containsKey("$ref"); hop++) {
      resolved = map(schemas.get(lastSegment(resolved.get("$ref"))));
    }
    return resolved;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> map(Object value) {
    return value instanceof Map<?, ?> found ? (Map<String, Object>) found : Map.of();
  }

  static List<?> list(Object value) {
    return value instanceof List<?> found ? found : List.of();
  }

  private static String lastSegment(Object reference) {
    String text = String.valueOf(reference);
    return text.substring(text.lastIndexOf('/') + 1);
  }
}
