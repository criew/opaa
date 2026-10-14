package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.common.Feature;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Every installation-wide feature flag ({@link Feature}) has exactly one required boolean in the
 * {@code FeaturesConfig} schema of {@code GET /api/v1/auth/config}, named by the camel-case form of
 * its property key - and the schema has no property without a flag behind it.
 */
class FeaturesSpecificationTest {

  @Test
  @SuppressWarnings("unchecked")
  void theFeaturesConfigSchemaHasOneRequiredBooleanPerFlag() throws Exception {
    Map<String, Object> spec;
    try (InputStream in =
        FeaturesSpecificationTest.class.getResourceAsStream("/openapi/opaa-api.yaml")) {
      spec = new Yaml().load(in);
    }
    Map<String, Object> schemas =
        (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");
    Map<String, Object> schema = (Map<String, Object>) schemas.get("FeaturesConfig");
    Map<String, Map<String, Object>> properties =
        (Map<String, Map<String, Object>>) schema.get("properties");
    Set<String> expected =
        Arrays.stream(Feature.values()).map(Feature::jsonName).collect(Collectors.toSet());

    assertThat(properties.keySet()).containsExactlyInAnyOrderElementsOf(expected);
    assertThat((List<String>) schema.get("required")).containsExactlyInAnyOrderElementsOf(expected);
    properties.forEach(
        (name, property) -> assertThat(property.get("type")).as(name).isEqualTo("boolean"));

    Map<String, Object> response = (Map<String, Object>) schemas.get("AuthConfigResponse");
    assertThat((List<String>) response.get("required")).contains("features");
  }

  @Test
  void theJsonNameIsTheCamelCaseOfThePropertyKey() {
    assertThat(Feature.PROMPT_LIBRARIES.jsonName()).isEqualTo("promptLibraries");
    assertThat(Feature.METADATA.jsonName()).isEqualTo("metadata");
    assertThat(Feature.EXTERNAL_ACCESS.jsonName()).isEqualTo("externalAccess");
    assertThat(Feature.EXTERNAL_ACCESS.enabledProperty())
        .isEqualTo("opaa.features.external-access.enabled");
  }
}
