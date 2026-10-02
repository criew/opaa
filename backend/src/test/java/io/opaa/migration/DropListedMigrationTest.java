package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The removal of the catalogue's findability flag (#2092): {@code assets.listed} with its partial
 * index, {@code asset_visibility_history.listed} with the cause that recorded a change of it, and
 * {@code knowledge_libraries.listed_cap}. Applied to an existing installation, i.e. the master
 * without these two files; the rows already there survive.
 */
class DropListedMigrationTest extends AbstractBaselineTest {

  private static final String RIGHTS_FILE = "db/changelog/rights/2026-10-02-drop-asset-listed.yaml";
  private static final String KNOWLEDGE_FILE =
      "db/changelog/knowledge/2026-10-02-drop-library-listed-cap.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(RIGHTS_FILE, KNOWLEDGE_FILE);
  }

  @Test
  void theListedColumnsAndTheirIndexAreGoneAndExistingRowsSurvive() throws Exception {
    UUID upload = insertLibrary();
    UUID connector = insertLibrary("RSS_FEED", "https://example.org/feed.xml");
    execute("UPDATE assets SET listed = true WHERE id = '" + connector + "'");
    execute(
        "INSERT INTO asset_visibility_history (id, asset_type, asset_id, organization_id, listed,"
            + " cause, valid_from) VALUES (gen_random_uuid(), 'KNOWLEDGE_LIBRARY', '"
            + connector
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', true, 'CREATED', now())");

    applyBoth();

    assertThat(columnExists("assets", "listed")).isFalse();
    assertThat(columnExists("asset_visibility_history", "listed")).isFalse();
    assertThat(columnExists("knowledge_libraries", "listed_cap")).isFalse();
    assertThat(columnExists("knowledge_libraries", "all_accounts_grant_allowed")).isTrue();
    assertThat(
            countWhere(
                "pg_indexes",
                "schemaname = current_schema() AND indexname = 'idx_assets_organization_listed'"))
        .isZero();
    assertThat(countWhere("assets", "id IN ('" + upload + "', '" + connector + "')")).isEqualTo(2);
    assertThat(countWhere("asset_visibility_history", "asset_id = '" + connector + "'"))
        .isEqualTo(1);
  }

  /** The share cap keeps its meaning: an upload library is never capped, a connector library is. */
  @Test
  void theShareCapStillBindsOnlyConnectorLibraries() throws Exception {
    applyBoth();
    UUID upload = insertLibrary();
    UUID connector = insertLibrary("RSS_FEED", "https://example.org/feed.xml");

    assertRejected(
        "UPDATE knowledge_libraries SET all_accounts_grant_allowed = false WHERE id = '"
            + upload
            + "'",
        "chk_knowledge_libraries_share_cap_upload_unrestricted");
    execute(
        "UPDATE knowledge_libraries SET all_accounts_grant_allowed = false WHERE id = '"
            + connector
            + "'");
    assertThat(
            booleanOf(
                "SELECT all_accounts_grant_allowed FROM knowledge_libraries WHERE id = '"
                    + connector
                    + "'"))
        .isFalse();
  }

  @Test
  void theHistoryNoLongerAcceptsAChangeOfFindabilityAsCause() throws Exception {
    applyBoth();
    UUID asset = insertAsset("KNOWLEDGE_LIBRARY", insertUser());

    assertRejected(
        visibilitySql(asset, "VISIBILITY_CHANGED"), "chk_asset_visibility_history_cause");
    execute(visibilitySql(asset, "EXTERNAL_ACCESS_CHANGED"));
    assertThat(countWhere("asset_visibility_history", "asset_id = '" + asset + "'")).isEqualTo(1);
  }

  private void applyBoth() throws Exception {
    applyChangelog(connection, RIGHTS_FILE);
    applyChangelog(connection, KNOWLEDGE_FILE);
  }

  private boolean columnExists(String table, String column) throws SQLException {
    return countWhere(
            "information_schema.columns",
            "table_schema = current_schema() AND table_name = '"
                + table
                + "' AND column_name = '"
                + column
                + "'")
        > 0;
  }

  private static String visibilitySql(UUID asset, String cause) {
    return "INSERT INTO asset_visibility_history (id, asset_type, asset_id, organization_id,"
        + " cause, valid_from) VALUES (gen_random_uuid(), 'KNOWLEDGE_LIBRARY', '"
        + asset
        + "', '"
        + SEEDED_ORGANIZATION_ID
        + "', '"
        + cause
        + "', now())";
  }
}
