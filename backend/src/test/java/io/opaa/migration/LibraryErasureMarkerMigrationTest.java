package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The erasure marker of a library, applied to an existing installation: the libraries already there
 * carry none, a marker takes its time and cause together, and a cause is an upper-case key.
 */
class LibraryErasureMarkerMigrationTest extends AbstractBaselineTest {

  private static final String FILE =
      "db/changelog/knowledge/2026-10-04-library-erasure-marker.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  @Test
  void existingLibrariesCarryNoMarkerAndAMarkerTakesTimeAndCauseTogether() throws Exception {
    UUID marked = insertLibrary("HTTP_DIRECTORY", "https://example.org/a");
    UUID other = insertLibrary();

    applyChangelog(connection, FILE);

    assertThat(countWhere("knowledge_libraries", "erasure_requested_at IS NOT NULL")).isZero();
    execute(
        "UPDATE knowledge_libraries SET erasure_requested_at = now(), erasure_cause = 'OWNER_REQUEST'"
            + " WHERE id = '"
            + marked
            + "'");
    assertThat(
            stringOf("SELECT erasure_cause FROM knowledge_libraries WHERE id = '" + marked + "'"))
        .isEqualTo("OWNER_REQUEST");
    assertRejected(
        "UPDATE knowledge_libraries SET erasure_requested_at = now() WHERE id = '" + other + "'",
        "chk_knowledge_libraries_erasure");
    assertRejected(
        "UPDATE knowledge_libraries SET erasure_requested_at = now(), erasure_cause = 'Akte 7'"
            + " WHERE id = '"
            + other
            + "'",
        "chk_knowledge_libraries_erasure");
  }
}
