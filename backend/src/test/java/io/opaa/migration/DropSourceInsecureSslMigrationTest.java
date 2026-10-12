package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The removal of the certificate switch of a library and of a connection profile, applied to an
 * existing installation (the master without both files): the columns go, their rows stay - also one
 * that had the check skipped - and an upload library is still held to carrying no source
 * configuration.
 */
class DropSourceInsecureSslMigrationTest extends AbstractBaselineTest {

  private static final String LIBRARY_FILE =
      "db/changelog/knowledge/2026-10-12-drop-library-source-insecure-ssl.yaml";
  private static final String PROFILE_FILE =
      "db/changelog/connections/2026-10-12-drop-profile-source-insecure-ssl.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(LIBRARY_FILE, PROFILE_FILE);
  }

  @Test
  void theColumnsGoAndTheRowsStay() throws Exception {
    UUID library = insertLibrary("HTTP_DIRECTORY", "https://intern.example.org/dokumente/");
    execute(
        "UPDATE knowledge_libraries SET source_insecure_ssl = true WHERE id = " + quoted(library));
    UUID profile = UUID.randomUUID();
    execute(
        "INSERT INTO connection_profiles (id, name, source_type, server_url, auth_method,"
            + " ownership, created_at, updated_at, version, source_insecure_ssl) VALUES ("
            + quoted(profile)
            + ", 'Zugang Bestand', 'PROFILE_PROBE', 'https://probe.example.org', 'NONE',"
            + " 'LIBRARY', now(), now(), 0, true)");

    applyChangelog(connection, LIBRARY_FILE);
    applyChangelog(connection, PROFILE_FILE);

    assertThat(columnExists("knowledge_libraries")).isFalse();
    assertThat(columnExists("connection_profiles")).isFalse();
    assertThat(stringOf("SELECT source_url FROM knowledge_libraries WHERE id = " + quoted(library)))
        .isEqualTo("https://intern.example.org/dokumente/");
    assertThat(countWhere("connection_profiles", "id = " + quoted(profile))).isEqualTo(1);
  }

  /** Dropping the column would take the check with it; the file recreates it without the column. */
  @Test
  void anUploadLibraryStillCarriesNoSourceConfiguration() throws Exception {
    applyChangelog(connection, LIBRARY_FILE);
    UUID upload = insertLibrary();
    String where = " WHERE id = " + quoted(upload);

    assertRejected(
        "UPDATE knowledge_libraries SET source_path = '/data'" + where,
        "chk_knowledge_libraries_upload_without_source");
    assertRejected(
        "UPDATE knowledge_libraries SET source_credentials = 'enc:v1:x'" + where,
        "chk_knowledge_libraries_upload_without_source");
    assertRejected(
        "UPDATE knowledge_libraries SET source_url = 'https://x.example'" + where,
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
                + "' AND column_name = 'source_insecure_ssl'")
        > 0;
  }
}
