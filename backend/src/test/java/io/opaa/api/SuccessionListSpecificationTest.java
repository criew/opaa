package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The promise of ADR-0036, Entscheidung 6 that only a test can keep: <b>the operational list is
 * object-bound in both directions</b> (Personalrat E1 and Z7). There is no query parameter, no sort
 * and no count by previous owner - and none by the acting person either. A reviewer cannot see the
 * absence of a parameter; this test can.
 */
class SuccessionListSpecificationTest {

  private static final String LIST_PATH = "/api/v1/admin/succession";

  /** Substrings that would make a person an evaluation axis of this list. */
  private static final List<String> FORBIDDEN_AXES =
      List.of("owner", "user", "person", "actor", "reviewed", "closedby", "sort", "order");

  private static Map<String, Object> spec;

  @BeforeAll
  @SuppressWarnings("unchecked")
  static void loadSpec() {
    try (InputStream in =
        SuccessionListSpecificationTest.class.getResourceAsStream("/openapi/opaa-api.yaml")) {
      spec = new Yaml().load(in);
    } catch (Exception e) {
      throw new RuntimeException("Failed to load opaa-api.yaml", e);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void theListTakesNoParameterByPreviousOwnerByActingPersonAndNoSortAtAll() {
    Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
    Map<String, Object> listPath = (Map<String, Object>) paths.get(LIST_PATH);
    assertThat(listPath).as("the operational list must be specified at " + LIST_PATH).isNotNull();
    Map<String, Object> get = (Map<String, Object>) listPath.get("get");
    List<Map<String, Object>> parameters =
        (List<Map<String, Object>>) get.getOrDefault("parameters", List.of());

    List<String> names =
        parameters.stream()
            .map(parameter -> String.valueOf(parameter.get("name")).toLowerCase())
            .collect(Collectors.toList());

    assertThat(names)
        .as("the list is complete and object-bound: kind picks the tab, the rest is paging")
        .containsExactlyInAnyOrder("kind", "page", "size");
    assertThat(names)
        .as(
            "no axis by previous owner or acting person, and no sort key that could become one"
                + " - ADR-0036, Entscheidung 6; Personalrat E1 and Z7")
        .noneMatch(name -> FORBIDDEN_AXES.stream().anyMatch(name::contains));
  }

  /**
   * The counter-check of the test above: it would pass just as well against a list that takes no
   * parameters at all, or against a misspelt path.
   */
  @Test
  @SuppressWarnings("unchecked")
  void theListIsActuallySpecifiedAndTakesTheThreeParametersItNeeds() {
    Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
    Map<String, Object> get =
        (Map<String, Object>) ((Map<String, Object>) paths.get(LIST_PATH)).get("get");

    assertThat(get.get("operationId")).isEqualTo("listSuccessionEntries");
    assertThat((List<?>) get.get("parameters")).hasSize(3);
  }
}
