package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The failure category of a run, applied to an existing installation: the runs already there keep
 * none, a failed run takes a category, and nothing but an upper-case key is one.
 */
class IndexingJobFailureCategoryMigrationTest extends AbstractBaselineTest {

  private static final String FILE =
      "db/changelog/knowledge/2026-10-04-indexing-job-failure-category.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  @Test
  void existingRunsKeepNoCategoryAndAFailedRunTakesOne() throws Exception {
    UUID failed = insertJob("FAILED");
    UUID completed = insertJob("COMPLETED");

    applyChangelog(connection, FILE);

    assertThat(countWhere("indexing_jobs", "failure_category IS NOT NULL")).isZero();
    assertThat(countWhere("indexing_jobs", "id IN ('" + failed + "', '" + completed + "')"))
        .isEqualTo(2);
    execute(
        "UPDATE indexing_jobs SET failure_category = 'NOT_CONNECTED' WHERE id = '" + failed + "'");
    assertThat(stringOf("SELECT failure_category FROM indexing_jobs WHERE id = '" + failed + "'"))
        .isEqualTo("NOT_CONNECTED");
    assertRejected(
        "UPDATE indexing_jobs SET failure_category = 'Datei geheim.pdf' WHERE id = '"
            + completed
            + "'",
        "chk_indexing_jobs_failure_category");
  }

  private UUID insertJob(String status) throws Exception {
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO indexing_jobs (id, status, last_progress_at, organization_id) VALUES ('"
            + id
            + "', '"
            + status
            + "', now(), '"
            + SEEDED_ORGANIZATION_ID
            + "')");
    return id;
  }
}
