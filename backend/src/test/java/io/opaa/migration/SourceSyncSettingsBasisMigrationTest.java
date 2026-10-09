package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code source_sync_state.settings_basis}: applied to an existing installation, a stored state
 * keeps its values and has no basis, so the next run under any settings discards it.
 */
class SourceSyncSettingsBasisMigrationTest extends AbstractBaselineTest {

  private static final String BASIS =
      "db/changelog/knowledge/2026-10-09-source-sync-settings-basis.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(BASIS);
  }

  @Test
  void aStoredStateKeepsItsValuesWithoutABasis() throws Exception {
    UUID library = insertLibrary("SMB", "smb://server/freigabe");
    UUID state = UUID.randomUUID();
    execute(
        "INSERT INTO source_sync_state (id, library_id, completed_scope_keys, updated_at)"
            + " VALUES ('"
            + state
            + "', '"
            + library
            + "', '/', now())");

    applyChangelog(connection, BASIS);

    assertThat(
            countWhere(
                "source_sync_state",
                "id = '" + state + "' AND settings_basis IS NULL AND completed_scope_keys = '/'"))
        .isOne();
    String basis = "a".repeat(64);
    execute(
        "UPDATE source_sync_state SET settings_basis = '" + basis + "' WHERE id = '" + state + "'");
    assertThat(countWhere("source_sync_state", "settings_basis = '" + basis + "'")).isOne();
  }
}
