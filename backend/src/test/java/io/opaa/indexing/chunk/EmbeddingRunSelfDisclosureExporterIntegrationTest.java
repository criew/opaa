package io.opaa.indexing.chunk;

import static org.assertj.core.api.Assertions.assertThat;

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
 * The category {@code indexing-runs} against Postgres: the re-embedding run the person started
 * appears with start, end and result, one started by another person does not. The singleton row
 * {@code embedding_index} is put back by {@code SeededRowRestorer}.
 */
@OpaaIntegrationTest
class EmbeddingRunSelfDisclosureExporterIntegrationTest {

  @Autowired private EmbeddingRunSelfDisclosureExporter exporter;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnOrganizationFixtures organizations;

  private SelfDisclosureTestData data;
  private UUID person;
  private UUID other;

  @BeforeEach
  void setUp() {
    data = SelfDisclosureTestData.newOrganization(jdbc, "sd-indexing-runs-org");
    person = data.user("petra-person");
    other = data.user("otto-fremd");
  }

  @AfterEach
  void tearDown() {
    data.removeAll(organizations);
  }

  @Test
  void exportsTheReembeddingThePersonStartedAndNotOneOfAnotherPerson() throws Exception {
    startedBy(person);

    CapturingSelfDisclosureSink mine = export();

    assertThat(mine.records("reembeddings"))
        .singleElement()
        .satisfies(
            record -> {
              assertThat(record).containsEntry("result", "abgeschlossen");
              assertThat(record.get("startedAt")).isNotNull();
              assertThat(record.get("finishedAt")).isNotNull();
            });
    assertThat(mine.text()).doesNotContain("SD-MESSAGE").doesNotContain(person.toString());

    startedBy(other);

    assertThat(export().text()).isEqualTo("{}");
  }

  private void startedBy(UUID account) {
    int updated =
        jdbc.update(
            "UPDATE embedding_index SET run_status = 'COMPLETED', run_started_at = now() -"
                + " interval '1 hour', run_finished_at = now(), run_started_by = ?,"
                + " run_organization_id = ?, run_message = 'SD-MESSAGE' WHERE id = 1",
            account,
            data.organizationId());
    assertThat(updated).as("the application records its embedding index at start").isEqualTo(1);
  }

  private CapturingSelfDisclosureSink export() throws Exception {
    CapturingSelfDisclosureSink sink =
        new CapturingSelfDisclosureSink(EmbeddingRunSelfDisclosureExporter.CATEGORY);
    exporter.export(new SelfDisclosureSubject(person, data.organizationId()), sink);
    return sink;
  }
}
