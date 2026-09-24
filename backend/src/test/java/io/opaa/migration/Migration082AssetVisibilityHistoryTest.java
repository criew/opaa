package io.opaa.migration;

import static io.opaa.migration.AssetShellMigrationFixtures.DEFAULT_ORGANIZATION;
import static io.opaa.migration.AssetShellMigrationFixtures.VISIBILITY_HISTORY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/082-asset-visibility-history.yaml} (#1899): the visibility history
 * becomes the shell's - renamed, keyed by asset type plus id, every row kept - and the deletion
 * marker is called {@code ASSET_DELETED} in both histories.
 */
class Migration082AssetVisibilityHistoryTest extends AbstractMigrationTest {

  private Connection connection;
  private AssetShellMigrationFixtures fixtures;

  @Override
  protected String baseFixtureChangelogPath() {
    return AssetShellMigrationFixtures.FIXTURE_CHAIN;
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    fixtures = new AssetShellMigrationFixtures(connection);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void everyIntervalMovesWithItsIdsAndIsTypedAsALibrary() throws Exception {
    UUID library = UUID.randomUUID();
    UUID open = visibilityInterval(library, "CREATED", false);
    UUID marker = visibilityInterval(library, "LIBRARY_DELETED", true);

    applyChangelog(connection, VISIBILITY_HISTORY);

    assertThat(
            fixtures.count(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema ="
                    + " current_schema() AND table_name = 'library_visibility_history'"))
        .isZero();
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM asset_visibility_history WHERE id = ? AND asset_id = ? AND"
                    + " asset_type = 'KNOWLEDGE_LIBRARY' AND cause = 'CREATED'",
                open,
                library))
        .isEqualTo(1);
    assertThat(fixtures.string("SELECT cause FROM asset_visibility_history WHERE id = ?", marker))
        .isEqualTo("ASSET_DELETED");
  }

  @Test
  void theOldDeletionCauseIsNoLongerStorable() throws Exception {
    applyChangelog(connection, VISIBILITY_HISTORY);

    assertThatThrownBy(
            () ->
                fixtures.execute(
                    "INSERT INTO asset_visibility_history (id, asset_type, asset_id,"
                        + " organization_id, visibility, listed, cause, valid_from, valid_to)"
                        + " VALUES (?, 'KNOWLEDGE_LIBRARY', ?, ?, 'PRIVATE', false,"
                        + " 'LIBRARY_DELETED', now(), now())",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    DEFAULT_ORGANIZATION))
        .hasMessageContaining("chk_asset_visibility_history_cause");
  }

  /** Two asset types sharing an id each keep their own open interval. */
  @Test
  void theOpenIntervalIsUniquePerTypeAndId() throws Exception {
    applyChangelog(connection, VISIBILITY_HISTORY);
    UUID asset = UUID.randomUUID();
    openInterval("KNOWLEDGE_LIBRARY", asset);
    openInterval("PROMPT_LIBRARY", asset);

    assertThatThrownBy(() -> openInterval("KNOWLEDGE_LIBRARY", asset))
        .hasMessageContaining("uk_asset_visibility_history_open");
  }

  @Test
  void theGrantHistoryDeletionMarkerIsRenamedToo() throws Exception {
    UUID user = fixtures.user();
    UUID renamed = UUID.randomUUID();
    fixtures.execute(
        "INSERT INTO asset_grant_history (id, asset_type, asset_id, organization_id, subject_type,"
            + " subject_user_id, role, cause, valid_from, valid_to) VALUES (?,"
            + " 'KNOWLEDGE_LIBRARY', ?, ?, 'USER', ?, 'VIEWER', 'LIBRARY_DELETED', now(), now())",
        renamed,
        UUID.randomUUID(),
        DEFAULT_ORGANIZATION,
        user);

    applyChangelog(connection, VISIBILITY_HISTORY);

    assertThat(fixtures.string("SELECT cause FROM asset_grant_history WHERE id = ?", renamed))
        .isEqualTo("ASSET_DELETED");
    assertThatThrownBy(
            () ->
                fixtures.execute(
                    "UPDATE asset_grant_history SET cause = 'LIBRARY_DELETED' WHERE id = ?",
                    renamed))
        .hasMessageContaining("chk_asset_grant_history_cause");
  }

  @Test
  void theChangelogIsReferencedByTheMasterChangelog() throws Exception {
    assertThat(AssetShellMigrationFixtures.masterChangelog()).contains(VISIBILITY_HISTORY);
  }

  private UUID visibilityInterval(UUID library, String cause, boolean closed) throws SQLException {
    UUID id = UUID.randomUUID();
    fixtures.execute(
        "INSERT INTO library_visibility_history (id, library_id, organization_id, visibility,"
            + " listed, cause, valid_from, valid_to) VALUES (?, ?, ?, 'PRIVATE', false, ?, now(), "
            + (closed ? "now()" : "NULL")
            + ")",
        id,
        library,
        DEFAULT_ORGANIZATION,
        cause);
    return id;
  }

  private void openInterval(String assetType, UUID asset) throws SQLException {
    fixtures.execute(
        "INSERT INTO asset_visibility_history (id, asset_type, asset_id, organization_id,"
            + " visibility, listed, cause, valid_from) VALUES (?, ?, ?, ?, 'PRIVATE', false,"
            + " 'CREATED', now())",
        UUID.randomUUID(),
        assetType,
        asset,
        DEFAULT_ORGANIZATION);
  }
}
