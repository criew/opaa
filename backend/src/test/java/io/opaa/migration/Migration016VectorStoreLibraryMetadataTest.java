package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Applies Liquibase changelog 016 in isolation against a database built from the real, versioned
 * changelog through changeSet 015 - the same pattern as {@code Migration015ReplaceSpaceKindTest},
 * with {@code test-master-through-015.yaml} as the pre-migration fixture. {@code
 * connection.setAutoCommit(true)} is called after every {@code liquibase.update(...)} call, and the
 * public schema is dropped and recreated between test methods, per the package Javadoc's mandatory
 * teardown pattern.
 *
 * <p>These tests assert against the <em>actual filter the search issues</em> ({@link
 * #matchedByLibraryFilter}), not merely against the presence of a metadata key. That distinction is
 * the whole point of #408: the chunks already carried {@code document_id} and {@code file_name}, so
 * any assertion phrased as "the metadata has content" would have passed on the broken state too.
 * What was missing is the one key the search filters on, in the exact JSONPath form {@code
 * PgVectorFilterExpressionConverter} produces.
 *
 * <p>{@code vector_store} is not under Liquibase control - Spring AI creates it at startup ({@code
 * PgVectorStore#initializeSchema}). These tests therefore create it the same way that code does,
 * and one of them deliberately omits it to cover the fresh-installation case the changeSet's
 * precondition exists for.
 */
@Testcontainers(disabledWithoutDocker = true)
class Migration016VectorStoreLibraryMetadataTest extends AbstractMigrationTest {

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";
  private static final String SYSTEM_LIBRARY_ID = "00000000-0000-0000-0000-000000000002";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-015.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void aChunkIndexedBeforeTheFilterExistedBecomesReachable() throws Exception {
    createVectorStoreTable();
    UUID document = insertDocument(SYSTEM_LIBRARY_ID);
    insertLegacyChunk(document);

    // The state this migration exists for: the document row already carries its library (migration
    // 012 backfilled it), the chunk does not - so the search filters it away and the whole corpus
    // is unreachable while still listed as INDEXED.
    assertThat(matchedByLibraryFilter(SYSTEM_LIBRARY_ID)).isZero();

    applyChangelog016();

    assertThat(matchedByLibraryFilter(SYSTEM_LIBRARY_ID)).isEqualTo(1);
  }

  @Test
  void theOtherMetadataKeysSurvive() throws Exception {
    createVectorStoreTable();
    UUID document = insertDocument(SYSTEM_LIBRARY_ID);
    insertLegacyChunk(document);

    applyChangelog016();

    // Citations are built from file_name, document_id and chunk_index (see
    // AnswerGenerationService#formatChunks). Replacing the metadata object instead of merging into
    // it would leave the corpus searchable but every answer uncitable.
    assertThat(metadataKey(document, "file_name")).isEqualTo("batman.md");
    assertThat(metadataKey(document, "chunk_index")).isEqualTo("0");
    assertThat(metadataKey(document, "organization_id")).isEqualTo(SEEDED_ORGANIZATION_ID);
  }

  @Test
  void aChunkThatAlreadyCarriesItsLibraryIsLeftUntouched() throws Exception {
    createVectorStoreTable();
    UUID otherLibrary = insertLibrary(UUID.randomUUID());
    UUID document = insertDocument(otherLibrary.toString());
    // Written by today's storeChunks, which sets the fields itself. The document row says one
    // library, this chunk deliberately says another: if the migration overwrote existing values
    // rather than only filling absent ones, this chunk would move libraries - a permission change,
    // not a repair.
    insertChunkWithLibrary(document, SYSTEM_LIBRARY_ID);

    applyChangelog016();

    assertThat(metadataKey(document, "library_id")).isEqualTo(SYSTEM_LIBRARY_ID);
  }

  @Test
  void aChunkWhoseDocumentIsGoneStaysUnchanged() throws Exception {
    createVectorStoreTable();
    insertLegacyChunk(UUID.randomUUID());

    applyChangelog016();

    // No library can be derived for an orphan, and inventing one would grant access on a guess.
    assertThat(chunksWithoutLibraryId()).isEqualTo(1);
  }

  @Test
  void aFreshInstallationWithoutTheTableIsNotBlocked() throws Exception {
    // Liquibase runs before Spring AI creates vector_store, so on a fresh database the table is
    // absent at this point. Without the precondition the changeSet would fail the whole startup.
    applyChangelog016();

    assertThat(changeSetIsRecorded()).isTrue();
  }

  private void applyChangelog016() throws Exception {
    applyChangelog(
        connection, "db/changelog/changes/016-backfill-vector-store-library-metadata.yaml");
  }

  /**
   * Creates {@code vector_store} exactly as {@code PgVectorStore#initializeSchema} does - notably
   * with {@code metadata} as {@code json} rather than {@code jsonb}, which is what forces the
   * migration's casts. The embedding dimension is irrelevant here (no vector is ever written), so
   * the smallest workable one is used.
   */
  private void createVectorStoreTable() throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE vector_store ("
              + "id uuid PRIMARY KEY, content text, metadata json, embedding vector(3))");
    }
  }

  private UUID insertDocument(String libraryId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO documents (id, file_name, file_path, status, library_id, organization_id) "
              + "VALUES ('"
              + id
              + "', 'batman.md', '/corpus/batman.md', 'INDEXED', '"
              + libraryId
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "')");
    }
    return id;
  }

  private UUID insertLibrary(UUID id) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO knowledge_libraries "
              + "(id, organization_id, name, owner_type, owner_user_id, owner_group_id,"
              + " visibility, listed, personal, created_at, updated_at) VALUES ('"
              + id
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', 'Bibliothek "
              + id
              + "', 'SYSTEM', NULL, NULL, 'PRIVATE', false, false, now(), now())");
    }
    return id;
  }

  /** A chunk as the pre-#202 storeChunks wrote it: no library_id, no organization_id. */
  private void insertLegacyChunk(UUID documentId) throws SQLException {
    insertChunk(
        "{\"file_name\": \"batman.md\", \"chunk_index\": 0, \"document_id\": \""
            + documentId
            + "\"}");
  }

  private void insertChunkWithLibrary(UUID documentId, String libraryId) throws SQLException {
    insertChunk(
        "{\"file_name\": \"batman.md\", \"chunk_index\": 0, \"document_id\": \""
            + documentId
            + "\", \"library_id\": \""
            + libraryId
            + "\", \"organization_id\": \""
            + SEEDED_ORGANIZATION_ID
            + "\"}");
  }

  private void insertChunk(String metadataJson) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO vector_store (id, content, metadata) VALUES ('"
              + UUID.randomUUID()
              + "', 'Batman ist 188 cm gross.', '"
              + metadataJson
              + "'::json)");
    }
  }

  /**
   * Counts the chunks the permission-aware search would actually retrieve for a library, using the
   * predicate {@code PgVectorFilterExpressionConverter} generates for the {@code library_id IN
   * (...)} expression {@code QueryService#libraryFilter} builds.
   */
  private int matchedByLibraryFilter(String libraryId) throws SQLException {
    return count(
        "SELECT count(*) FROM vector_store WHERE metadata::jsonb @@ '($.\"library_id\" == \""
            + libraryId
            + "\")'::jsonpath");
  }

  private int chunksWithoutLibraryId() throws SQLException {
    return count(
        "SELECT count(*) FROM vector_store WHERE NOT jsonb_exists(metadata::jsonb, 'library_id')");
  }

  private boolean changeSetIsRecorded() throws SQLException {
    return count(
            "SELECT count(*) FROM databasechangelog WHERE id ="
                + " '016-backfill-vector-store-library-metadata'")
        == 1;
  }

  private String metadataKey(UUID documentId, String key) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT metadata->>'"
                    + key
                    + "' FROM vector_store WHERE metadata->>'document_id' = '"
                    + documentId
                    + "'")) {
      return result.next() ? result.getString(1) : null;
    }
  }

  private int count(String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      result.next();
      return result.getInt(1);
    }
  }
}
