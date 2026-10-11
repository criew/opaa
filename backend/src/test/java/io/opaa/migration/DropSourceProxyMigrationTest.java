package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The removal of the proxy of a library and of a connection profile, applied to an existing
 * installation (the master without both files): the columns go, their rows stay, and an upload
 * library is still held to carrying no source configuration.
 */
class DropSourceProxyMigrationTest extends AbstractBaselineTest {

  private static final String LIBRARY_FILE =
      "db/changelog/knowledge/2026-10-11-drop-library-source-proxy.yaml";
  private static final String PROFILE_FILE =
      "db/changelog/connections/2026-10-11-drop-profile-source-proxy.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(LIBRARY_FILE, PROFILE_FILE);
  }

  @Test
  void theColumnsGoAndTheRowsStay() throws Exception {
    UUID library = insertLibrary("RSS_FEED", "https://example.org/feed.xml");
    execute(
        "UPDATE knowledge_libraries SET source_proxy = 'proxy.example.org:3128' WHERE id = "
            + quoted(library));
    UUID profile = UUID.randomUUID();
    execute(
        "INSERT INTO connection_profiles (id, name, source_type, server_url, auth_method,"
            + " ownership, created_at, updated_at, version, source_proxy) VALUES ("
            + quoted(profile)
            + ", 'Zugang Bestand', 'PROFILE_PROBE', 'https://probe.example.org', 'NONE',"
            + " 'LIBRARY', now(), now(), 0, 'proxy.example.org:3128')");

    applyChangelog(connection, LIBRARY_FILE);
    applyChangelog(connection, PROFILE_FILE);

    assertThat(columnExists("knowledge_libraries")).isFalse();
    assertThat(columnExists("connection_profiles")).isFalse();
    assertThat(stringOf("SELECT source_url FROM knowledge_libraries WHERE id = " + quoted(library)))
        .isEqualTo("https://example.org/feed.xml");
    assertThat(countWhere("connection_profiles", "id = " + quoted(profile))).isEqualTo(1);
  }

  /** Dropping the column would take the check with it; the file recreates it without the column. */
  @Test
  void anUploadLibraryStillCarriesNoSourceConfiguration() throws Exception {
    applyChangelog(connection, LIBRARY_FILE);
    UUID upload = insertLibrary();
    String where = " WHERE id = " + quoted(upload);

    assertRejected(
        "UPDATE knowledge_libraries SET source_url = 'https://x.example'" + where,
        "chk_knowledge_libraries_upload_without_source");
    assertRejected(
        "UPDATE knowledge_libraries SET source_insecure_ssl = true" + where,
        "chk_knowledge_libraries_upload_without_source");
    assertRejected(
        "UPDATE knowledge_libraries SET source_settings = '{}'" + where,
        "chk_knowledge_libraries_upload_without_source");
  }

  private boolean columnExists(String table) throws Exception {
    return countWhere(
            "information_schema.columns",
            "table_schema = current_schema() AND table_name = '"
                + table
                + "' AND column_name = 'source_proxy'")
        > 0;
  }
}
