package io.opaa.migration;

import static io.opaa.migration.AssetShellMigrationFixtures.CREATE_ASSETS;
import static io.opaa.migration.AssetShellMigrationFixtures.DEFAULT_ORGANIZATION;
import static io.opaa.migration.AssetShellMigrationFixtures.SPACE_ASSOCIATIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/083-space-asset-associations-asset.yaml} (#1900): an association
 * names an asset of any type - the stock keeps its rows, the foreign key points at the shell, and
 * the cascade and the uniqueness per space hold as before.
 */
class Migration083SpaceAssetAssociationsAssetTest extends AbstractMigrationTest {

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
  void theStockKeepsItsRowsUnderTheNewColumn() throws Exception {
    UUID owner = fixtures.user();
    UUID library = fixtures.legacyLibrary("Bibliothek", "USER", owner, null, "PRIVATE");
    UUID space = fixtures.space(owner);
    UUID association = associate(space, "library_id", library, owner);
    applyChangelog(connection, CREATE_ASSETS);

    applyChangelog(connection, SPACE_ASSOCIATIONS);

    assertThat(fixtures.columnExists("space_asset_associations", "library_id")).isFalse();
    assertThat(
            fixtures.string(
                "SELECT asset_id FROM space_asset_associations WHERE id = ?", association))
        .isEqualTo(library.toString());
  }

  @Test
  void anAssociationFollowsItsAssetOutOfExistence() throws Exception {
    UUID owner = fixtures.user();
    UUID library = fixtures.legacyLibrary("Bibliothek", "USER", owner, null, "PRIVATE");
    UUID space = fixtures.space(owner);
    UUID association = associate(space, "library_id", library, owner);
    applyChangelog(connection, CREATE_ASSETS);
    applyChangelog(connection, SPACE_ASSOCIATIONS);

    fixtures.execute("DELETE FROM assets WHERE id = ?", library);

    assertThat(
            fixtures.count(
                "SELECT count(*) FROM space_asset_associations WHERE id = ?", association))
        .isZero();
  }

  @Test
  void anAssociationWithoutAnAssetIsNotStorableAndTheSameAssetOnlyOncePerSpace() throws Exception {
    UUID owner = fixtures.user();
    UUID library = fixtures.legacyLibrary("Bibliothek", "USER", owner, null, "PRIVATE");
    UUID space = fixtures.space(owner);
    applyChangelog(connection, CREATE_ASSETS);
    applyChangelog(connection, SPACE_ASSOCIATIONS);

    assertThatThrownBy(() -> associate(space, "asset_id", UUID.randomUUID(), owner))
        .hasMessageContaining("fk_space_asset_associations_asset_organization");
    associate(space, "asset_id", library, owner);
    assertThatThrownBy(() -> associate(space, "asset_id", library, owner))
        .hasMessageContaining("uk_space_asset_associations_space_asset");
  }

  @Test
  void theChangelogIsReferencedByTheMasterChangelog() throws Exception {
    assertThat(AssetShellMigrationFixtures.masterChangelog()).contains(SPACE_ASSOCIATIONS);
  }

  private UUID associate(UUID space, String column, UUID asset, UUID creator) throws SQLException {
    UUID id = UUID.randomUUID();
    fixtures.execute(
        "INSERT INTO space_asset_associations (id, space_id, "
            + column
            + ", organization_id, created_by_user_id) VALUES (?, ?, ?, ?, ?)",
        id,
        space,
        asset,
        DEFAULT_ORGANIZATION,
        creator);
    return id;
  }
}
