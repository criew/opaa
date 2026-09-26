package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/092-connector-settings.yaml} (#1977, ADR-0038): the Confluence
 * columns and the space selection table are gone, the source type is an open key the database only
 * checks for its form, {@code source_settings} holds an object, and an {@code UPLOAD} library still
 * carries no source configuration.
 */
class Migration092ConnectorSettingsTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH = "db/changelog/changes/092-connector-settings.yaml";

  private Connection connection;
  private AssetShellMigrationFixtures fixtures;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-091.yaml";
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
  void theFixtureChainStillCarriesTheConfluenceColumnsAndRefusesAnUnknownType() throws Exception {
    assertThat(fixtures.columnExists("knowledge_libraries", "source_confluence_edition")).isTrue();
    assertThat(tableExists("knowledge_library_confluence_spaces")).isTrue();
    UUID owner = fixtures.user();

    assertThatThrownBy(() -> library(owner, "WIKI", "https://wiki.example.org", null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_source_");
  }

  @Test
  void theConfluenceColumnsAndTheSpaceTableAreGone() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    connection.setAutoCommit(true);

    assertThat(fixtures.columnExists("knowledge_libraries", "source_confluence_edition")).isFalse();
    assertThat(
            fixtures.columnExists(
                "knowledge_libraries", "source_confluence_full_sync_interval_days"))
        .isFalse();
    assertThat(tableExists("knowledge_library_confluence_spaces")).isFalse();
  }

  @Test
  void aNewTypeKeyNeedsNoSchemaChangeButMustBeAKey() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    connection.setAutoCommit(true);
    UUID owner = fixtures.user();

    UUID library = library(owner, "WIKI_2", "https://wiki.example.org", "{\"space\": \"A\"}");
    fixtures.execute(
        "INSERT INTO documents (file_name, file_path, source_type, library_id, organization_id)"
            + " VALUES ('a.html', 'a.html', 'WIKI_2', ?, ?)",
        library,
        AssetShellMigrationFixtures.DEFAULT_ORGANIZATION);

    assertThatThrownBy(() -> library(owner, "wiki", "https://wiki.example.org", null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_source_type");
    assertThatThrownBy(
            () ->
                fixtures.execute(
                    "INSERT INTO documents (file_name, file_path, source_type, library_id,"
                        + " organization_id) VALUES ('b.html', 'b.html', 'wiki-2', ?, ?)",
                    library,
                    AssetShellMigrationFixtures.DEFAULT_ORGANIZATION))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_documents_source_type");
  }

  @Test
  void theSettingsAreAnObjectWhoseContentOnlyTheConnectorChecks() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    connection.setAutoCommit(true);
    UUID owner = fixtures.user();

    // formerly refused per type: a CONFLUENCE row without edition, an S3 row without scopes
    library(owner, "CONFLUENCE", "https://wiki.example.org", "{\"spaces\": []}");
    library(owner, "S3", "https://s3.example.org", "{\"scopes\": []}");

    assertThatThrownBy(() -> library(owner, "S3", "https://s3.example.org", "[]"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_source_settings");
  }

  @Test
  void anUploadLibraryStillCarriesNoSourceConfiguration() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    connection.setAutoCommit(true);
    UUID owner = fixtures.user();

    library(owner, "UPLOAD", null, null);
    assertThatThrownBy(() -> library(owner, "UPLOAD", "https://example.org", null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_upload_without_source");
    assertThatThrownBy(() -> library(owner, "UPLOAD", null, "{}"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_upload_without_source");
  }

  @Test
  void theChangelogIsReferencedByTheMasterChangelog() throws Exception {
    assertThat(AssetShellMigrationFixtures.masterChangelog()).contains(CHANGELOG_PATH);
  }

  /** A library in the post-080 shape with the given type, address and settings. */
  private UUID library(UUID owner, String sourceType, String sourceUrl, String settings)
      throws SQLException {
    UUID library = UUID.randomUUID();
    fixtures.execute(
        "INSERT INTO assets (id, asset_type, organization_id, name, owner_type, owner_user_id)"
            + " VALUES (?, 'KNOWLEDGE_LIBRARY', ?, 'Bibliothek', 'USER', ?)",
        library,
        AssetShellMigrationFixtures.DEFAULT_ORGANIZATION,
        owner);
    fixtures.execute(
        "INSERT INTO knowledge_libraries (id, organization_id, source_type, source_url,"
            + " source_settings) VALUES (?, ?, ?, ?, CAST(? AS jsonb))",
        library,
        AssetShellMigrationFixtures.DEFAULT_ORGANIZATION,
        sourceType,
        sourceUrl,
        settings);
    return library;
  }

  private boolean tableExists(String table) throws SQLException {
    return fixtures.count(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema()"
                + " AND table_name = ?",
            table)
        == 1;
  }
}
