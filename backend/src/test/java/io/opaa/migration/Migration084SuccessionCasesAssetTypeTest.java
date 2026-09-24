package io.opaa.migration;

import static io.opaa.migration.AssetShellMigrationFixtures.DEFAULT_ORGANIZATION;
import static io.opaa.migration.AssetShellMigrationFixtures.SUCCESSION_CASES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/084-succession-cases-asset-type.yaml}: a library case becomes an
 * asset case of type {@code KNOWLEDGE_LIBRARY}, space and group cases stay as they are, and the
 * asset type is set exactly for asset cases.
 */
class Migration084SuccessionCasesAssetTypeTest extends AbstractMigrationTest {

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
  void aLibraryCaseBecomesAnAssetCaseAndTheOthersStay() throws Exception {
    UUID library = openCase("KNOWLEDGE_LIBRARY");
    UUID space = openCase("SPACE");
    UUID group = openCase("GROUP");

    applyChangelog(connection, SUCCESSION_CASES);

    assertThat(typeOf(library)).isEqualTo("ASSET/KNOWLEDGE_LIBRARY");
    assertThat(typeOf(space)).isEqualTo("SPACE/-");
    assertThat(typeOf(group)).isEqualTo("GROUP/-");
  }

  @Test
  void theAssetTypeIsSetExactlyForAssetCases() throws Exception {
    applyChangelog(connection, SUCCESSION_CASES);

    assertThatThrownBy(() -> insertCase("ASSET", null))
        .hasMessageContaining("chk_succession_cases_asset_type");
    assertThatThrownBy(() -> insertCase("SPACE", "KNOWLEDGE_LIBRARY"))
        .hasMessageContaining("chk_succession_cases_asset_type");
    assertThatThrownBy(() -> insertCase("KNOWLEDGE_LIBRARY", null))
        .hasMessageContaining("chk_succession_cases_object_type");
    insertCase("ASSET", "PROMPT_LIBRARY");
  }

  @Test
  void theChangelogIsReferencedByTheMasterChangelog() throws Exception {
    assertThat(AssetShellMigrationFixtures.masterChangelog()).contains(SUCCESSION_CASES);
  }

  private UUID openCase(String objectType) throws SQLException {
    UUID objectId = UUID.randomUUID();
    fixtures.execute(
        "INSERT INTO succession_cases (id, organization_id, kind, object_type, object_id,"
            + " first_seen_at, last_seen_at) VALUES (?, ?, 'OPEN_SUCCESSION', ?, ?, now(), now())",
        UUID.randomUUID(),
        DEFAULT_ORGANIZATION,
        objectType,
        objectId);
    return objectId;
  }

  private void insertCase(String objectType, String assetType) throws SQLException {
    fixtures.execute(
        "INSERT INTO succession_cases (id, organization_id, kind, object_type, asset_type,"
            + " object_id, first_seen_at, last_seen_at) VALUES (?, ?, 'OPEN_SUCCESSION', ?, ?, ?,"
            + " now(), now())",
        UUID.randomUUID(),
        DEFAULT_ORGANIZATION,
        objectType,
        assetType,
        UUID.randomUUID());
  }

  private String typeOf(UUID objectId) throws SQLException {
    return fixtures.string(
        "SELECT object_type || '/' || coalesce(asset_type, '-') FROM succession_cases"
            + " WHERE object_id = ?",
        objectId);
  }
}
