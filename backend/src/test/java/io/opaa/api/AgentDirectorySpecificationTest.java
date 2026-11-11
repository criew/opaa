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
 * The promise of the agent directory, held against its response schema: it describes objects, not
 * people. No property reachable from the directory's answer names a usage figure, a user, an owner
 * or a last use, the directory takes no parameter to select by, and a library appears only by name
 * - no id the caller could follow into its content.
 */
class AgentDirectorySpecificationTest {

  private static final List<String> FORBIDDEN =
      List.of(
          "count",
          "usage",
          "used",
          "user",
          "person",
          "owner",
          "author",
          "actor",
          "member",
          "last",
          "createdby",
          "updatedby",
          "reason");

  private static final List<String> DIRECTORY_PATHS =
      List.of("/api/v1/agent-directory", "/api/v1/agent-directory/export");

  private static Map<String, Object> spec;

  @BeforeAll
  static void loadSpec() {
    try (InputStream in =
        AgentDirectorySpecificationTest.class.getResourceAsStream("/openapi/opaa-api.yaml")) {
      spec = new Yaml().load(in);
    } catch (Exception e) {
      throw new RuntimeException("Failed to load opaa-api.yaml", e);
    }
  }

  @Test
  void theDirectoryCarriesNoUsageFigureNoPersonAndNoLockReason() {
    Set<String> properties = new TreeSet<>();
    collectProperties(
        Map.of("$ref", "#/components/schemas/AgentDirectoryResponse"), properties, new HashSet<>());

    assertThat(properties)
        .as("the counter-check: the walk reaches the fields the directory does carry")
        .contains("instruction", "libraryNames", "toolNames", "responsibility", "lockedSince");
    assertThat(properties)
        .as("no usage figure, no person, no lock reason")
        .noneMatch(name -> FORBIDDEN.stream().anyMatch(word -> name.toLowerCase().contains(word)));
  }

  @Test
  void aLibraryAppearsByNameOnlyAndTheDirectoryTakesNoSelection() {
    Map<String, Object> entry = schema("AgentDirectoryEntry");
    assertThat(properties(entry)).doesNotContainKey("libraries").doesNotContainKey("libraryIds");
    assertThat(
            ((Map<?, ?>) ((Map<?, ?>) properties(entry).get("libraryNames")).get("items"))
                .get("type"))
        .isEqualTo("string");

    for (String path : DIRECTORY_PATHS) {
      Map<?, ?> item = (Map<?, ?>) ((Map<?, ?>) spec.get("paths")).get(path);
      assertThat(item).as(path).isNotNull();
      assertThat(item.get("parameters")).as(path).isNull();
      assertThat(((Map<?, ?>) item.get("get")).get("parameters")).as(path).isNull();
    }
  }

  @SuppressWarnings("unchecked")
  private static void collectProperties(Object node, Set<String> names, Set<String> visited) {
    if (node instanceof Map<?, ?> map) {
      Object ref = map.get("$ref");
      if (ref instanceof String reference) {
        if (visited.add(reference)) {
          collectProperties(resolve(reference), names, visited);
        }
        return;
      }
      if (map.get("properties") instanceof Map<?, ?> properties) {
        properties.keySet().forEach(key -> names.add(String.valueOf(key)));
      }
      for (Object child : ((Map<String, Object>) map).values()) {
        collectProperties(child, names, visited);
      }
    } else if (node instanceof List<?> list) {
      for (Object child : new ArrayList<>(list)) {
        collectProperties(child, names, visited);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static Object resolve(String reference) {
    Object current = spec;
    for (String segment : reference.substring(2).split("/")) {
      current = ((Map<String, Object>) current).get(segment);
    }
    return current;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> schema(String name) {
    return (Map<String, Object>) resolve("#/components/schemas/" + name);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> properties(Map<String, Object> schema) {
    return (Map<String, Object>) schema.get("properties");
  }
}
