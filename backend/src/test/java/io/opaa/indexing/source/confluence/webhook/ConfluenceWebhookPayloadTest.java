package io.opaa.indexing.source.confluence.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ConfluenceWebhookPayloadTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  private java.util.Set<String> ids(String json) {
    return ConfluenceWebhookPayload.pageIds(json.getBytes(StandardCharsets.UTF_8), mapper);
  }

  @Test
  void readsTheDataCenterPageEventShape() {
    assertThat(
            ids(
                "{\"timestamp\":1725350400000,\"event\":\"page_updated\",\"userKey\":\"abc\","
                    + "\"page\":{\"id\":102,\"spaceKey\":\"ENG\",\"title\":\"Abschnitt 1.1\","
                    + "\"self\":\"https://wiki/pages/viewpage.action?pageId=102\"}}"))
        .containsExactly("102");
  }

  @Test
  void readsAttachmentEventsByTheirPage() {
    assertThat(ids("{\"event\":\"attachment_created\",\"attachment\":{\"id\":900,\"pageId\":102}}"))
        .containsExactly("102");
    assertThat(
            ids(
                "{\"event\":\"attachment_removed\",\"attachment\":{\"id\":900,"
                    + "\"container\":{\"id\":\"103\"}}}"))
        .containsExactly("103");
  }

  @Test
  void readsTheFlatShapesAnAutomationRuleSends() {
    assertThat(ids("{\"event\":\"page_trashed\",\"pageId\":\"102\"}")).containsExactly("102");
    assertThat(ids("{\"pageIds\":[\"102\",103,\"102\",\" \"]}")).containsExactly("102", "103");
    assertThat(ids("{\"content\":{\"id\":\"104\",\"type\":\"page\"}}")).containsExactly("104");
  }

  @Test
  void namesNoPageForAnEmptyNonJsonOrUnrelatedBody() {
    assertThat(ConfluenceWebhookPayload.pageIds(new byte[0], mapper)).isEmpty();
    assertThat(ConfluenceWebhookPayload.pageIds(null, mapper)).isEmpty();
    assertThat(ids("nicht json")).isEmpty();
    assertThat(ids("[1,2]")).isEmpty();
    assertThat(ids("{\"event\":\"space_created\",\"space\":{\"key\":\"ENG\"}}")).isEmpty();
    assertThat(ids("{\"page\":{\"id\":\"\"}}")).isEmpty();
  }
}
