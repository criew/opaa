package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code knowledge/2026-10-11-drop-library-source-proxy.yaml} was reworded so its recreated upload
 * check no longer names {@code source_insecure_ssl}. An installation that ran the earlier wording
 * holds that version's checksum; starting the master on it must accept the stored checksum, not
 * re-run the file, and end with the TLS column and the earlier clause gone; any other stored
 * checksum still stops the start.
 */
class ProxyCheckRewordingMigrationTest extends AbstractBaselineTest {

  private static final String PROXY_FILE =
      "db/changelog/knowledge/2026-10-11-drop-library-source-proxy.yaml";
  private static final String TLS_FILE =
      "db/changelog/knowledge/2026-10-12-drop-library-source-insecure-ssl.yaml";
  private static final String PROFILE_TLS_FILE =
      "db/changelog/connections/2026-10-12-drop-profile-source-insecure-ssl.yaml";

  /** The checksum Liquibase 5 stored for the file's wording before the rewording. */
  private static final String EARLIER_CHECKSUM = "9:3ddd6e925cb1750197f0d897d126533d";

  /** The checksum of the reworded file. */
  private static final String REWORDED_CHECKSUM = "9:e8034ac7421929413949ccdb70247c5b";

  private static final String EARLIER_CHECK =
      "CHECK ((((source_type)::text <> 'UPLOAD'::text) OR ((source_path IS NULL) AND (source_url IS"
          + " NULL) AND (source_credentials IS NULL) AND (source_insecure_ssl = false) AND"
          + " (source_settings IS NULL))))";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(PROXY_FILE, TLS_FILE, PROFILE_TLS_FILE);
  }

  @Test
  void anInstallationThatRanTheEarlierWordingStartsWithoutAChecksumFailure() throws Exception {
    ranTheEarlierWording(EARLIER_CHECKSUM);
    UUID library = insertLibrary("HTTP_DIRECTORY", "https://intern.example.org/dokumente/");

    applyChangelog(connection, MasterChangelog.PATH);

    assertThat(
            countWhere(
                "databasechangelog",
                "id = 'knowledge-2026-10-11-drop-library-source-proxy' AND md5sum = '"
                    + EARLIER_CHECKSUM
                    + "'"))
        .as("the file is not re-run and keeps the stored checksum")
        .isEqualTo(1);
    assertThat(
            countWhere(
                "databasechangelog",
                "id = 'knowledge-2026-10-12-drop-library-source-insecure-ssl'"))
        .isEqualTo(1);
    assertThat(columnExists("source_insecure_ssl")).isFalse();
    assertThat(columnExists("source_proxy")).isFalse();
    assertThat(uploadCheck()).doesNotContain("source_insecure_ssl").contains("source_settings");
    assertThat(countWhere("knowledge_libraries", "id = " + quoted(library))).isEqualTo(1);
  }

  /** An installation without the file takes the reworded one and stores its own checksum. */
  @Test
  void anInstallationWithoutTheFileTakesTheRewordedOne() throws Exception {
    applyChangelog(connection, MasterChangelog.PATH);

    assertThat(
            countWhere(
                "databasechangelog",
                "id = 'knowledge-2026-10-11-drop-library-source-proxy' AND md5sum = '"
                    + REWORDED_CHECKSUM
                    + "'"))
        .isEqualTo(1);
    assertThat(columnExists("source_insecure_ssl")).isFalse();
    assertThat(uploadCheck()).doesNotContain("source_insecure_ssl");
  }

  /** Only the earlier wording's checksum is admitted: any other stored one stops the start. */
  @Test
  void aForeignStoredChecksumStopsTheStart() throws Exception {
    ranTheEarlierWording("9:00000000000000000000000000000000");

    assertThatThrownBy(() -> applyChangelog(connection, MasterChangelog.PATH))
        .hasStackTraceContaining("knowledge-2026-10-11-drop-library-source-proxy");
  }

  /** The earlier wording's effect and its changelog row, as an installation from main holds it. */
  private void ranTheEarlierWording(String storedChecksum) throws Exception {
    execute(
        "ALTER TABLE knowledge_libraries DROP CONSTRAINT"
            + " chk_knowledge_libraries_upload_without_source");
    execute("ALTER TABLE knowledge_libraries DROP COLUMN source_proxy");
    execute(
        "ALTER TABLE knowledge_libraries ADD CONSTRAINT"
            + " chk_knowledge_libraries_upload_without_source "
            + EARLIER_CHECK);
    execute(
        "INSERT INTO databasechangelog (id, author, filename, dateexecuted, orderexecuted,"
            + " exectype, md5sum) VALUES ('knowledge-2026-10-11-drop-library-source-proxy', 'opaa',"
            + " '"
            + PROXY_FILE
            + "', now(), (SELECT coalesce(max(orderexecuted), 0) + 1 FROM databasechangelog),"
            + " 'EXECUTED', '"
            + storedChecksum
            + "')");
    assertThat(uploadCheck()).contains("source_insecure_ssl");
  }

  private String uploadCheck() throws Exception {
    return stringOf(
        "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
            + " WHERE conname = 'chk_knowledge_libraries_upload_without_source'");
  }

  private boolean columnExists(String column) throws Exception {
    return countWhere(
            "information_schema.columns",
            "table_schema = current_schema() AND table_name = 'knowledge_libraries'"
                + " AND column_name = '"
                + column
                + "'")
        > 0;
  }
}
