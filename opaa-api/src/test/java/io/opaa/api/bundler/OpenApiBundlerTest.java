package io.opaa.api.bundler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class OpenApiBundlerTest {

  private static final String ROOT =
      """
      openapi: 3.0.3
      info:
        title: T
        version: 0.1.0
      """;

  private static final String SPACES =
      """
      tags:
        - name: space
          description: Spaces.
      paths:
        /spaces:
          get:
            operationId: listSpaces
            tags: [space]
            responses:
              "200":
                description: ok
                content:
                  application/json:
                    schema:
                      $ref: "#/components/schemas/Space"
      components:
        schemas:
          Space:
            type: object
            properties:
              created:
                type: string
                example: 2026-01-01
              role:
                $ref: "#/components/schemas/Role"
      """;

  private static final String COMMON =
      """
      components:
        schemas:
          Role:
            type: string
            enum: [OFF, ON]
      """;

  @TempDir Path dir;

  @Test
  @SuppressWarnings("unchecked")
  void mergesRootThenFragmentsInNameOrder() throws IOException {
    write("root.yaml", ROOT);
    write("spaces.yaml", SPACES);
    write("common.yaml", COMMON);

    String bundle = OpenApiBundler.bundle(dir);

    Map<String, Object> doc = new Yaml().load(bundle);
    assertThat(doc.keySet()).containsExactly("openapi", "info", "tags", "paths", "components");
    Map<String, Object> schemas =
        (Map<String, Object>) ((Map<String, Object>) doc.get("components")).get("schemas");
    assertThat(schemas.keySet()).containsExactly("Role", "Space");
    assertThat((List<Object>) doc.get("tags")).hasSize(1);
  }

  @Test
  void keepsTheSourceFormOfEveryScalar() throws IOException {
    write("root.yaml", ROOT);
    write("spaces.yaml", SPACES);
    write("common.yaml", COMMON);

    String bundle = OpenApiBundler.bundle(dir);

    // re-read by a YAML 1.1 parser, these plain scalars must resolve exactly as in the fragment
    assertThat(bundle).contains("example: 2026-01-01").contains("enum: [OFF, ON]");
    assertThat(bundle).contains("\"200\":");
  }

  @Test
  void rejectsAComponentDefinedInTwoFragments() throws IOException {
    write("root.yaml", ROOT);
    write("spaces.yaml", SPACES);
    write("common.yaml", COMMON);
    write("other.yaml", COMMON);

    assertThatThrownBy(() -> OpenApiBundler.bundle(dir))
        .hasMessageContaining("other.yaml")
        .hasMessageContaining("components/schemas 'Role' is defined in more than one fragment");
  }

  @Test
  void rejectsAnUnresolvableRef() throws IOException {
    write("root.yaml", ROOT);
    write("spaces.yaml", SPACES);

    assertThatThrownBy(() -> OpenApiBundler.bundle(dir))
        .hasMessageContaining("'#/components/schemas/Role' does not resolve");
  }

  @Test
  void rejectsATagDeclaredInAnotherFragment() throws IOException {
    write("root.yaml", ROOT);
    write("common.yaml", COMMON);
    write("spaces.yaml", SPACES.replace("tags:\n  - name: space\n    description: Spaces.\n", ""));
    write("tags.yaml", "tags:\n  - name: space\n");

    assertThatThrownBy(() -> OpenApiBundler.bundle(dir))
        .hasMessageContaining("spaces.yaml: get /spaces uses tag 'space'");
  }

  @Test
  void rejectsAnOperationWithTwoTags() throws IOException {
    write("root.yaml", ROOT);
    write("common.yaml", COMMON);
    write("spaces.yaml", SPACES.replace("tags: [space]", "tags: [space, admin]"));

    assertThatThrownBy(() -> OpenApiBundler.bundle(dir))
        .hasMessageContaining("get /spaces must carry exactly one tag");
  }

  @Test
  void rejectsOtherTopLevelKeysInAFragment() throws IOException {
    write("root.yaml", ROOT);
    write("common.yaml", COMMON + "info:\n  title: X\n");

    assertThatThrownBy(() -> OpenApiBundler.bundle(dir))
        .hasMessageContaining("common.yaml: top-level key 'info' is not allowed");
  }

  @Test
  void namesTheFragmentOfASyntaxError() throws IOException {
    write("root.yaml", ROOT);
    write("common.yaml", "components:\n  schemas:\n    Role: [unclosed\n");

    assertThatThrownBy(() -> OpenApiBundler.bundle(dir)).hasMessageStartingWith("common.yaml: ");
  }

  @Test
  void rejectsAnchorsAndAliases() throws IOException {
    write("root.yaml", ROOT);
    write(
        "common.yaml",
        "components:\n  schemas:\n    Role: &role\n      type: string\n    Other: *role\n");

    assertThatThrownBy(() -> OpenApiBundler.bundle(dir))
        .hasMessageContaining("common.yaml: YAML anchors and aliases are not allowed");
  }

  private void write(String name, String content) throws IOException {
    Files.writeString(dir.resolve(name), content);
  }
}
