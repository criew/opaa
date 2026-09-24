package io.opaa.migration;

import static io.opaa.migration.AssetShellMigrationFixtures.CREATE_ASSETS;
import static io.opaa.migration.AssetShellMigrationFixtures.DEFAULT_ORGANIZATION;
import static io.opaa.migration.AssetShellMigrationFixtures.DROP_SHELL_COLUMNS;
import static io.opaa.migration.AssetShellMigrationFixtures.GRANTS_FOREIGN_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/081-asset-grants-asset-foreign-key.yaml} (#1899): the grant table
 * points at the asset shell again - the per-type delete trigger of 038 is gone, the cascade holds
 * for every asset type, and a grant naming a missing asset or a different type is not storable.
 */
class Migration081AssetGrantsAssetForeignKeyTest extends AbstractMigrationTest {

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
  void theTriggerIsGoneAndDeletingTheAssetStillTakesItsGrants() throws Exception {
    UUID owner = fixtures.user();
    UUID library = fixtures.legacyLibrary("Bibliothek", "USER", owner, null, "PRIVATE");
    UUID grant = fixtures.userGrant(library, owner, "OWNER", owner);
    applyShell();

    applyChangelog(connection, GRANTS_FOREIGN_KEY);

    assertThat(
            fixtures.count(
                "SELECT count(*) FROM pg_trigger WHERE tgname ="
                    + " 'trg_knowledge_libraries_delete_asset_grants'"))
        .isZero();
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM pg_proc WHERE proname ="
                    + " 'knowledge_libraries_delete_asset_grants'"))
        .isZero();
    fixtures.execute("DELETE FROM assets WHERE id = ?", library);
    assertThat(fixtures.count("SELECT count(*) FROM asset_grants WHERE id = ?", grant)).isZero();
  }

  @Test
  void aGrantOnAMissingAssetIsNotStorable() throws Exception {
    UUID owner = fixtures.user();
    applyShell();
    applyChangelog(connection, GRANTS_FOREIGN_KEY);

    assertThatThrownBy(() -> fixtures.userGrant(UUID.randomUUID(), owner, "VIEWER", owner))
        .hasMessageContaining("fk_asset_grants_asset_organization");
  }

  @Test
  void aGrantNamingAnotherTypeThanItsAssetIsNotStorable() throws Exception {
    UUID owner = fixtures.user();
    UUID library = seedAfterShell(owner);
    applyChangelog(connection, GRANTS_FOREIGN_KEY);

    assertThatThrownBy(
            () ->
                fixtures.execute(
                    "INSERT INTO asset_grants (id, asset_type, asset_id, organization_id,"
                        + " subject_type, subject_user_id, role) VALUES (?, 'PROMPT_LIBRARY', ?,"
                        + " ?, 'USER', ?, 'VIEWER')",
                    UUID.randomUUID(),
                    library,
                    DEFAULT_ORGANIZATION,
                    owner))
        .hasMessageContaining("fk_asset_grants_asset_organization");
  }

  @Test
  void anOrphanedGrantIsRemovedSoTheKeyCanBeCreated() throws Exception {
    UUID owner = fixtures.user();
    UUID library = fixtures.legacyLibrary("Bibliothek", "USER", owner, null, "PRIVATE");
    UUID kept = fixtures.userGrant(library, owner, "OWNER", owner);
    applyShell();
    fixtures.execute(
        "ALTER TABLE knowledge_libraries DISABLE TRIGGER trg_knowledge_libraries_delete_asset_grants");
    UUID orphanLibrary = fixtures.shellLibrary(owner);
    UUID orphan = fixtures.userGrant(orphanLibrary, owner, "OWNER", owner);
    fixtures.execute("DELETE FROM assets WHERE id = ?", orphanLibrary);

    applyChangelog(connection, GRANTS_FOREIGN_KEY);

    assertThat(fixtures.count("SELECT count(*) FROM asset_grants WHERE id = ?", orphan)).isZero();
    assertThat(fixtures.count("SELECT count(*) FROM asset_grants WHERE id = ?", kept)).isEqualTo(1);
  }

  @Test
  void theChangelogIsReferencedByTheMasterChangelog() throws Exception {
    assertThat(AssetShellMigrationFixtures.masterChangelog()).contains(GRANTS_FOREIGN_KEY);
  }

  private void applyShell() throws Exception {
    applyChangelog(connection, CREATE_ASSETS);
    applyChangelog(connection, DROP_SHELL_COLUMNS);
  }

  /** Applies 079/080 and seeds one library in the post-080 shape. */
  private UUID seedAfterShell(UUID owner) throws Exception {
    applyShell();
    return fixtures.shellLibrary(owner);
  }
}
