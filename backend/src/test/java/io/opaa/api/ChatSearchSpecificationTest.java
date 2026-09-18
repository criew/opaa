package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The privacy-relevant shape of the chat search in the specification (docs/features/chat-list.md,
 * "Datenschutz und Personalvertretung"): the term travels in the body - as a query parameter it
 * would land in the access log of the reverse proxy - and a page carries no total.
 */
class ChatSearchSpecificationTest {

  private static final String PATH = "/api/v1/spaces/{spaceId}/chats/search";

  private static Map<String, Object> spec;

  @BeforeAll
  static void loadSpec() throws Exception {
    try (InputStream in =
        ChatSearchSpecificationTest.class.getResourceAsStream("/openapi/opaa-api.yaml")) {
      spec = new Yaml().load(in);
    }
  }

  @Test
  void theSearchIsAPostWhoseOnlyParameterIsTheSpaceInThePath() {
    Map<String, Object> path = map(map(spec, "paths"), PATH);

    assertThat(path.keySet()).containsExactlyInAnyOrder("parameters", "post");
    List<Map<String, Object>> parameters = list(path, "parameters");
    assertThat(parameters).hasSize(1);
    assertThat(parameters.getFirst()).containsEntry("name", "spaceId").containsEntry("in", "path");
    Map<String, Object> post = map(path, "post");
    assertThat(post).doesNotContainKey("parameters");
    assertThat(map(post, "requestBody")).containsEntry("required", true);
  }

  @Test
  void theTermIsARequiredBodyField() {
    Map<String, Object> request = schema("ChatSearchRequest");

    assertThat(list(request, "required")).containsExactly("query");
    assertThat(map(request, "properties")).containsKey("query");
  }

  @Test
  void aPageReportsWhetherMoreFollowsButNoTotal() {
    Map<String, Object> response = schema("ChatSearchResponse");

    assertThat(map(response, "properties").keySet()).containsExactlyInAnyOrder("hits", "hasMore");
  }

  private static Map<String, Object> schema(String name) {
    return map(map(map(spec, "components"), "schemas"), name);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Map<String, Object> parent, String key) {
    Object value = parent.get(key);
    assertThat(value).as(key).isInstanceOf(Map.class);
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static <T> List<T> list(Map<String, Object> parent, String key) {
    Object value = parent.get(key);
    assertThat(value).as(key).isInstanceOf(List.class);
    return (List<T>) value;
  }
}
