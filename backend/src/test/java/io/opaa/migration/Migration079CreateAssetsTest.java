package io.opaa.migration;

import static io.opaa.migration.AssetShellMigrationFixtures.CREATE_ASSETS;
import static io.opaa.migration.AssetShellMigrationFixtures.DEFAULT_ORGANIZATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/079-create-assets.yaml} (#1899): every library of the stock gets
 * its shell row with the same id and the same shell fields, the creator is read from the creator's
 * own OWNER grant, and the type row can no longer exist without its shell.
 */
class Migration079CreateAssetsTest extends AbstractMigrationTest {

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
  void everyLibraryGetsItsShellRowWithTheSameIdAndFields() throws Exception {
    UUID owner = fixtures.user();
    UUID group = fixtures.group();
    UUID personOwned = fixtures.legacyLibrary("Privat", "USER", owner, null, "PRIVATE");
    UUID groupOwned = fixtures.legacyLibrary("Referat", "GROUP", null, group, "ORGANIZATION");

    applyChangelog(connection, CREATE_ASSETS);

    assertThat(
            fixtures.count(
                "SELECT count(*) FROM assets WHERE id = ? AND asset_type = 'KNOWLEDGE_LIBRARY'"
                    + " AND organization_id = ? AND name = 'Privat' AND description ="
                    + " 'Beschreibung' AND owner_type = 'USER' AND owner_user_id = ? AND"
                    + " owner_group_id IS NULL AND visibility = 'PRIVATE' AND listed AND origin"
                    + " = 'LOCAL' AND created_at = timestamptz '2026-01-02 03:04:05+00' AND"
                    + " updated_at = timestamptz '2026-02-03 04:05:06+00'",
                personOwned,
                DEFAULT_ORGANIZATION,
                owner))
        .isEqualTo(1);
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM assets WHERE id = ? AND owner_type = 'GROUP' AND"
                    + " owner_group_id = ? AND owner_user_id IS NULL AND visibility ="
                    + " 'ORGANIZATION'",
                groupOwned,
                group))
        .isEqualTo(1);
  }

  @Test
  void theCreatorIsReadFromTheSelfIssuedOwnerGrantAndStaysEmptyWithoutOne() throws Exception {
    UUID creator = fixtures.user();
    UUID other = fixtures.user();
    UUID withCreator = fixtures.legacyLibrary("Mit", "USER", creator, null, "PRIVATE");
    fixtures.userGrant(withCreator, creator, "OWNER", creator);
    fixtures.userGrant(withCreator, other, "OWNER", creator);
    UUID withoutCreator = fixtures.legacyLibrary("Ohne", "USER", creator, null, "PRIVATE");
    fixtures.userGrant(withoutCreator, other, "OWNER", creator);

    applyChangelog(connection, CREATE_ASSETS);

    assertThat(fixtures.string("SELECT created_by_user_id FROM assets WHERE id = ?", withCreator))
        .isEqualTo(creator.toString());
    assertThat(
            fixtures.string("SELECT created_by_user_id FROM assets WHERE id = ?", withoutCreator))
        .as("an OWNER grant somebody else issued names no creator")
        .isNull();
  }

  @Test
  void deletingTheShellTakesTheLibraryRowWithIt() throws Exception {
    UUID owner = fixtures.user();
    UUID library = fixtures.legacyLibrary("Weg", "USER", owner, null, "PRIVATE");
    applyChangelog(connection, CREATE_ASSETS);

    fixtures.execute("DELETE FROM assets WHERE id = ?", library);

    assertThat(fixtures.count("SELECT count(*) FROM knowledge_libraries WHERE id = ?", library))
        .isZero();
  }

  @Test
  void aLibraryRowWithoutItsShellIsRejected() throws Exception {
    UUID owner = fixtures.user();
    applyChangelog(connection, CREATE_ASSETS);

    assertThatThrownBy(() -> fixtures.legacyLibrary("Ohne Schale", "USER", owner, null, "PRIVATE"))
        .hasMessageContaining("fk_knowledge_libraries_asset");
  }

  @Test
  void anAssetTypeOutsideTheAllowedFormIsRejected() throws Exception {
    UUID owner = fixtures.user();
    applyChangelog(connection, CREATE_ASSETS);

    assertThatThrownBy(
            () ->
                fixtures.execute(
                    "INSERT INTO assets (id, asset_type, organization_id, name, owner_type,"
                        + " owner_user_id, visibility) VALUES (?, 'knowledge-library', ?, 'x',"
                        + " 'USER', ?, 'PRIVATE')",
                    UUID.randomUUID(),
                    DEFAULT_ORGANIZATION,
                    owner))
        .hasMessageContaining("chk_assets_asset_type_format");
  }

  @Test
  void anOwnerThatStillOwnsAnAssetCannotBeDeleted() throws Exception {
    UUID owner = fixtures.user();
    fixtures.legacyLibrary("Eigentum", "USER", owner, null, "PRIVATE");
    applyChangelog(connection, CREATE_ASSETS);

    assertThatThrownBy(() -> fixtures.execute("DELETE FROM users WHERE id = ?", owner))
        .hasMessageContaining("fk_");
  }

  @Test
  void theChangelogIsReferencedByTheMasterChangelog() throws Exception {
    assertThat(AssetShellMigrationFixtures.masterChangelog()).contains(CREATE_ASSETS);
  }
}
