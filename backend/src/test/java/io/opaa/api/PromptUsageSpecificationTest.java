package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The promise of docs/features/spaces-and-assets.md, "Kein personenbezogener Auswertungspfad", for
 * the prompt used in the chat (#1903): which prompt built a question is visible at that question
 * and nowhere else. No operation takes the used prompt as a parameter, no operation but the one
 * query takes it in a request, and only the operations returning one single chat carry it in a
 * response - there is no list by prompt or by person across conversations. A reviewer cannot see
 * the absence of a filter; this test can.
 */
class PromptUsageSpecificationTest {

  private static final Set<String> USED_PROMPT_PROPERTIES =
      Set.of("usedPromptId", "usedPromptTitle");

  /** The operations that return one chat of its author, with its messages. */
  private static final Set<String> SINGLE_CHAT_OPERATIONS =
      Set.of("createChat", "getChat", "updateChat");

  private static final List<String> FORBIDDEN_SELECTION_AXES =
      List.of("user", "person", "owner", "author", "actor", "sort", "order", "used", "count");

  private static Map<String, Object> spec;
  private static Map<String, Object> schemas;

  @BeforeAll
  @SuppressWarnings("unchecked")
  static void loadSpec() {
    try (InputStream in =
        PromptUsageSpecificationTest.class.getResourceAsStream("/openapi/opaa-api.yaml")) {
      spec = new Yaml().load(in);
    } catch (Exception e) {
      throw new RuntimeException("Failed to load opaa-api.yaml", e);
    }
    schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");
  }

  @Test
  void noOperationTakesTheUsedPromptAsAParameter() {
    List<String> offenses = new ArrayList<>();
    forEachOperation(
        (path, method, operation) -> {
          for (Map<String, Object> parameter : parameters(path, operation)) {
            String name = String.valueOf(parameter.get("name")).toLowerCase();
            if (name.contains("usedprompt")
                || (name.contains("prompt") && !path.startsWith("/api/v1/prompt-libraries/"))) {
              offenses.add(method.toUpperCase() + " " + path + " ?" + parameter.get("name"));
            }
          }
        });

    assertThat(offenses)
        .as("a prompt is never a selection axis outside its own library's routes")
        .isEmpty();
  }

  @Test
  void onlyTheQueryTakesTheUsedPromptInItsRequest() {
    Set<String> requestSchemas = new TreeSet<>();
    forEachOperation(
        (path, method, operation) -> {
          if (carriesUsedPrompt(requestBodySchemas(operation))) {
            requestSchemas.add(String.valueOf(operation.get("operationId")));
          }
        });

    assertThat(requestSchemas).containsExactly("submitQuery");
  }

  @Test
  void onlyTheSingleChatOperationsReturnTheUsedPrompt() {
    Set<String> carrying = new TreeSet<>();
    forEachOperation(
        (path, method, operation) -> {
          if (carriesUsedPrompt(successResponseSchemas(operation))) {
            carrying.add(String.valueOf(operation.get("operationId")));
          }
        });

    assertThat(carrying)
        .as(
            "the used prompt is shown at its question, inside one chat of its author - never in a"
                + " list across conversations")
        .containsExactlyInAnyOrderElementsOf(SINGLE_CHAT_OPERATIONS);
  }

  /**
   * The counter-check of the tests above: they would pass just as well against a specification
   * without the property at all.
   */
  @Test
  void theUsedPromptIsActuallySpecifiedOnTheQuestionAndTheMessage() {
    assertThat(properties("QueryRequest")).containsKey("usedPromptId");
    assertThat(properties("ChatMessageResponse")).containsKeys("usedPromptId", "usedPromptTitle");
  }

  @Test
  @SuppressWarnings("unchecked")
  void theSelectionTakesOnlyTheSpaceAndCarriesNoUsageAndNoPerson() {
    Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
    Map<String, Object> get =
        (Map<String, Object>)
            ((Map<String, Object>) paths.get("/api/v1/prompts/available")).get("get");

    assertThat(parameters("/api/v1/prompts/available", get))
        .extracting(parameter -> parameter.get("name"))
        .containsExactly("spaceId");
    assertThat(properties("AvailablePrompt").keySet())
        .as("no usage figure, no person, no sort key")
        .noneMatch(
            name ->
                FORBIDDEN_SELECTION_AXES.stream()
                    .anyMatch(axis -> name.toLowerCase().contains(axis)));
  }

  private interface OperationVisitor {
    void visit(String path, String method, Map<String, Object> operation);
  }

  @SuppressWarnings("unchecked")
  private static void forEachOperation(OperationVisitor visitor) {
    Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
    paths.forEach(
        (path, item) ->
            ((Map<String, Object>) item)
                .forEach(
                    (method, operation) -> {
                      if (operation instanceof Map<?, ?> map && map.containsKey("responses")) {
                        visitor.visit(path, method, (Map<String, Object>) map);
                      }
                    }));
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> parameters(String path, Map<String, Object> operation) {
    List<Map<String, Object>> all = new ArrayList<>();
    Map<String, Object> pathItem =
        (Map<String, Object>) ((Map<String, Object>) spec.get("paths")).get(path);
    for (Object source :
        List.of(
            pathItem.getOrDefault("parameters", List.of()),
            operation.getOrDefault("parameters", List.of()))) {
      for (Map<String, Object> parameter : (List<Map<String, Object>>) source) {
        all.add(resolve(parameter));
      }
    }
    return all;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> requestBodySchemas(Map<String, Object> operation) {
    Object body = operation.get("requestBody");
    return body == null ? List.of() : List.of(resolve((Map<String, Object>) body));
  }

  @SuppressWarnings("unchecked")
  private static List<Object> successResponseSchemas(Map<String, Object> operation) {
    List<Object> result = new ArrayList<>();
    ((Map<Object, Object>) operation.get("responses"))
        .forEach(
            (status, response) -> {
              if (String.valueOf(status).startsWith("2")) {
                result.add(resolve((Map<String, Object>) response));
              }
            });
    return result;
  }

  private static boolean carriesUsedPrompt(List<Object> roots) {
    Set<String> visited = new HashSet<>();
    for (Object root : roots) {
      if (reaches(root, visited)) {
        return true;
      }
    }
    return false;
  }

  /** Walks a spec node - schema, response or body - through every $ref it reaches. */
  @SuppressWarnings("unchecked")
  private static boolean reaches(Object node, Set<String> visited) {
    if (node instanceof Map<?, ?> map) {
      Object ref = map.get("$ref");
      if (ref instanceof String reference) {
        if (!visited.add(reference)) {
          return false;
        }
        return reaches(resolveReference(reference), visited);
      }
      Object properties = map.get("properties");
      if (properties instanceof Map<?, ?> props
          && props.keySet().stream().anyMatch(USED_PROMPT_PROPERTIES::contains)) {
        return true;
      }
      for (Object child : ((Map<String, Object>) map).values()) {
        if (reaches(child, visited)) {
          return true;
        }
      }
    } else if (node instanceof List<?> list) {
      for (Object child : list) {
        if (reaches(child, visited)) {
          return true;
        }
      }
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> resolve(Map<String, Object> node) {
    Object ref = node.get("$ref");
    return ref instanceof String reference
        ? (Map<String, Object>) resolveReference(reference)
        : node;
  }

  @SuppressWarnings("unchecked")
  private static Object resolveReference(String reference) {
    Object current = spec;
    for (String segment : reference.substring(2).split("/")) {
      current = ((Map<String, Object>) current).get(segment);
    }
    return current;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> properties(String schema) {
    return (Map<String, Object>) ((Map<String, Object>) schemas.get(schema)).get("properties");
  }
}
