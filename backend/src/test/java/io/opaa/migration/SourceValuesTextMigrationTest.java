package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Container keys, file names and the RSS feed's response headers become {@code text}: applied to an
 * existing installation, stored values of the former full width keep their value and longer ones
 * fit.
 */
class SourceValuesTextMigrationTest extends AbstractBaselineTest {

  private static final String KNOWLEDGE =
      "db/changelog/knowledge/2026-10-11-source-values-text.yaml";
  private static final String CONNECTORS =
      "db/changelog/connectors/2026-10-11-rss-feed-state-headers-text.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(KNOWLEDGE, CONNECTORS);
  }

  @Test
  void theDocumentAndRevisitColumnsBecomeTextAndKeepTheirValues() throws Exception {
    UUID library = insertLibrary("DROPBOX", null);
    UUID document = insertDocument(library, "dropbox:/a.txt");
    String containerKey = "k".repeat(255);
    String fileName = "n".repeat(500);
    execute(
        "UPDATE documents SET source_container_key = "
            + quoted(containerKey)
            + ", file_name = "
            + quoted(fileName)
            + " WHERE id = "
            + quoted(document));
    UUID state = insertSyncState(library);
    insertRevisit(state, containerKey);
    assertThat(columnType("documents", "source_container_key")).isEqualTo("character varying");

    applyChangelog(connection, KNOWLEDGE);

    assertThat(columnType("documents", "source_container_key")).isEqualTo("text");
    assertThat(columnType("documents", "file_name")).isEqualTo("text");
    assertThat(columnType("source_sync_revisits", "container_key")).isEqualTo("text");
    assertThat(
            stringOf("SELECT source_container_key FROM documents WHERE id = " + quoted(document)))
        .isEqualTo(containerKey);
    assertThat(stringOf("SELECT file_name FROM documents WHERE id = " + quoted(document)))
        .isEqualTo(fileName);
    assertThat(stringOf("SELECT container_key FROM source_sync_revisits")).isEqualTo(containerKey);

    String longer = "/Projekte/" + "Bauabschnitt Nord/".repeat(60);
    execute(
        "UPDATE documents SET source_container_key = "
            + quoted(longer)
            + ", file_name = "
            + quoted(longer + ".txt")
            + " WHERE id = "
            + quoted(document));
    insertRevisit(state, longer);
  }

  @Test
  void theFeedHeaderColumnsBecomeTextAndKeepTheirValues() throws Exception {
    UUID library = insertLibrary("RSS_FEED", "https://rathaus.example/aktuelles.rss");
    String etag = "e".repeat(500);
    String lastModified = "m".repeat(200);
    execute(
        "INSERT INTO rss_feed_state (id, feed_url, etag, last_modified, updated_at, library_id)"
            + " VALUES (gen_random_uuid(), 'https://rathaus.example/aktuelles.rss', "
            + quoted(etag)
            + ", "
            + quoted(lastModified)
            + ", now(), "
            + quoted(library)
            + ")");

    applyChangelog(connection, CONNECTORS);

    assertThat(columnType("rss_feed_state", "etag")).isEqualTo("text");
    assertThat(columnType("rss_feed_state", "last_modified")).isEqualTo("text");
    assertThat(stringOf("SELECT etag FROM rss_feed_state")).isEqualTo(etag);
    assertThat(stringOf("SELECT last_modified FROM rss_feed_state")).isEqualTo(lastModified);
    execute(
        "UPDATE rss_feed_state SET etag = "
            + quoted("e".repeat(600))
            + ", last_modified = "
            + quoted("m".repeat(300)));
  }

  private UUID insertSyncState(UUID library) throws Exception {
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO source_sync_state (id, library_id, updated_at) VALUES ("
            + quoted(id)
            + ", "
            + quoted(library)
            + ", now())");
    return id;
  }

  private void insertRevisit(UUID state, String containerKey) throws Exception {
    execute(
        "INSERT INTO source_sync_revisits (id, sync_state_id, container_key, created_at) VALUES"
            + " (gen_random_uuid(), "
            + quoted(state)
            + ", "
            + quoted(containerKey)
            + ", now())");
  }

  private String columnType(String table, String column) throws Exception {
    return stringOf(
        "SELECT data_type FROM information_schema.columns WHERE table_schema = current_schema()"
            + " AND table_name = '"
            + table
            + "' AND column_name = '"
            + column
            + "'");
  }
}
