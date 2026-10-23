package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.compliance.SelfDisclosureLabels;
import io.opaa.compliance.SelfDisclosureSubject;
import io.opaa.test.CapturingSelfDisclosureSink;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import io.opaa.test.SelfDisclosureTestData;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The category {@code chat-cleanup-holds} against Postgres: the person's own hold with its time and
 * "andere Person" as the one who held it, nothing of another account's hold.
 */
@OpaaIntegrationTest
class ChatCleanupHoldSelfDisclosureExporterIntegrationTest {

  @Autowired private ChatCleanupHoldSelfDisclosureExporter exporter;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnOrganizationFixtures organizations;

  private SelfDisclosureTestData data;
  private UUID person;
  private UUID other;

  @BeforeEach
  void setUp() {
    data = SelfDisclosureTestData.newOrganization(jdbc, "sd-chat-hold-org");
    person = data.user("petra-person");
    other = data.user("otto-fremd");
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM chat_cleanup_holds WHERE organization_id = ?", data.organizationId());
    data.removeAll(organizations);
  }

  @Test
  void exportsTheOwnHoldWithAnotherPersonAsHolderAndNoForeignHold() throws Exception {
    hold(other, "now() - interval '2 days'");

    CapturingSelfDisclosureSink none = export();
    assertThat(none.text()).isEqualTo("{}");

    hold(person, "now() - interval '1 day'");
    CapturingSelfDisclosureSink sink = export();

    assertThat(sink.records("holds"))
        .singleElement()
        .satisfies(
            record -> {
              assertThat(record).containsEntry("heldBy", SelfDisclosureLabels.OTHER_PERSON);
              assertThat(record.get("heldSince")).isNotNull();
            });
    assertThat(sink.text())
        .doesNotContain("otto")
        .doesNotContain(other.toString())
        .doesNotContain(person.toString());
  }

  private void hold(UUID account, String heldAt) {
    jdbc.update(
        "INSERT INTO chat_cleanup_holds (user_id, organization_id, held_at) VALUES (?, ?, "
            + heldAt
            + ")",
        account,
        data.organizationId());
  }

  private CapturingSelfDisclosureSink export() throws Exception {
    CapturingSelfDisclosureSink sink =
        new CapturingSelfDisclosureSink(ChatCleanupHoldSelfDisclosureExporter.CATEGORY);
    exporter.export(new SelfDisclosureSubject(person, data.organizationId()), sink);
    return sink;
  }
}
