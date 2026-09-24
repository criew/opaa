package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/091-assets-listed-index.yaml} (#1904): the listed part of the
 * catalog query gets a partial index over the organization and the type, restricted to listed
 * assets.
 */
class Migration091AssetsListedIndexTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH = "db/changelog/changes/091-assets-listed-index.yaml";

  private Connection connection;
  private AssetShellMigrationFixtures fixtures;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-088.yaml";
  }

  @BeforeEach
  void setUp() throws SQLException {
    connection = connect();
    connection.setAutoCommit(true);
    fixtures = new AssetShellMigrationFixtures(connection);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void theFixtureChainDoesNotYetKnowTheIndex() throws Exception {
    assertThat(indexDefinition()).isNull();
  }

  @Test
  void theIndexCoversOrganizationAndTypeOfTheListedAssetsOnly() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(indexDefinition())
        .contains("assets USING btree (organization_id, asset_type)")
        .endsWith("WHERE listed");
  }

  @Test
  void theChangelogIsReferencedByTheMasterChangelog() throws Exception {
    assertThat(AssetShellMigrationFixtures.masterChangelog()).contains(CHANGELOG_PATH);
  }

  private String indexDefinition() throws SQLException {
    return fixtures.string(
        "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema()"
            + " AND indexname = 'idx_assets_organization_listed'");
  }
}
