package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code source_sync_state.scan_progress} and {@code source_sync_presence}: applied to an existing
 * installation, a stored state keeps its values with no round in progress, and a presence row goes
 * with its document and with its state.
 */
class SourceSyncRoundMigrationTest extends AbstractBaselineTest {

  private static final String PROGRESS =
      "db/changelog/knowledge/2026-10-04-source-sync-scan-progress.yaml";
  private static final String PRESENCE =
      "db/changelog/knowledge/2026-10-04-source-sync-presence.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(PROGRESS, PRESENCE);
  }

  @Test
  void aStoredStateHasNoRoundInProgressAndPresenceFollowsDocumentAndState() throws Exception {
    UUID library = insertLibrary("SMB", "smb://server/freigabe");
    UUID kept = insertDocument(library, "smb://server/freigabe/a.txt");
    UUID dropped = insertDocument(library, "smb://server/freigabe/b.txt");
    UUID state = UUID.randomUUID();
    execute(
        "INSERT INTO source_sync_state (id, library_id, completed_scope_keys, updated_at)"
            + " VALUES ('"
            + state
            + "', '"
            + library
            + "', '/', now())");

    applyChangelog(connection, PROGRESS);
    applyChangelog(connection, PRESENCE);

    assertThat(
            countWhere(
                "source_sync_state",
                "id = '" + state + "' AND scan_progress IS NULL AND completed_scope_keys = '/'"))
        .isOne();
    UUID scan = UUID.randomUUID();
    for (UUID document : List.of(kept, dropped)) {
      execute(
          "INSERT INTO source_sync_presence (document_id, sync_state_id, scan_id) VALUES ('"
              + document
              + "', '"
              + state
              + "', '"
              + scan
              + "')");
    }
    execute("DELETE FROM documents WHERE id = '" + dropped + "'");
    assertThat(countWhere("source_sync_presence", "sync_state_id = '" + state + "'")).isOne();
    execute("DELETE FROM source_sync_state WHERE id = '" + state + "'");
    assertThat(countRows("source_sync_presence")).isZero();
  }
}
