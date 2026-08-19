package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies Liquibase changelog 031 in isolation against a database built from the real, versioned
 * changelog through changeSet 030 - the same pattern as {@code Migration028...Test}, with {@code
 * test-master-through-030.yaml} as the pre-migration fixture (see that fixture's own comment).
 *
 * <p>#521: the single, well-known system library and every row dependent on it (documents,
 * vector_store chunks, indexing_jobs, asset_grants) are deleted outright - a maintainer decision,
 * not a data migration. {@code vector_store} is not under Liquibase control (Spring AI creates it
 * at startup, {@code PgVectorStore#initializeSchema}); these tests create it the same way that code
 * does, mirroring {@code Migration016VectorStoreLibraryMetadataTest}, and one of them deliberately
 * omits it to cover the fresh-installation case the first changeSet's precondition exists for.
 *
 * <p>Several tests use a second, hand-inserted SYSTEM-owned library with a random id (not the
 * well-known one) - the migration's own comment explains why every DELETE matches on {@code
 * owner_type = 'SYSTEM'} as well as the fixed id: {@code chk_knowledge_libraries_owner} never
 * capped the well-known library at exactly one SYSTEM-owned row, so a stray one must be caught too.
 */
class Migration031DeleteSystemLibraryTest extends AbstractMigrationTest {

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";
  private static final String SYSTEM_LIBRARY_ID = "00000000-0000-0000-0000-000000000002";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-030.yaml";
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
  void deletesTheSystemLibraryRowItself() throws Exception {
    assertThat(libraryExists(SYSTEM_LIBRARY_ID)).isTrue();

    applyChangelog031();

    assertThat(libraryExists(SYSTEM_LIBRARY_ID)).isFalse();
  }

  @Test
  void deletesAStraySystemOwnedLibraryNotJustTheWellKnownId() throws Exception {
    UUID strayId = insertSystemOwnedLibrary();

    applyChangelog031();

    assertThat(libraryExists(strayId.toString())).isFalse();
  }

  @Test
  void deletesEveryDocumentInTheSystemLibraryButLeavesOtherLibrariesUntouched() throws Exception {
    UUID otherLibrary = insertLibrary(UUID.randomUUID(), insertUser(UUID.randomUUID()));
    UUID systemDocument = insertDocument(SYSTEM_LIBRARY_ID);
    UUID otherDocument = insertDocument(otherLibrary.toString());

    applyChangelog031();

    assertThat(documentExists(systemDocument)).isFalse();
    assertThat(documentExists(otherDocument)).isTrue();
  }

  @Test
  void deletesIndexingJobsThatTargetedTheSystemLibraryRatherThanOrphaningThem() throws Exception {
    UUID job = insertIndexingJob(SYSTEM_LIBRARY_ID);

    applyChangelog031();

    // fk_indexing_jobs_library alone (ON DELETE SET NULL) would merely have set library_id to NULL
    // and left the row behind - #031's own changeSet deletes it outright instead.
    assertThat(indexingJobExists(job)).isFalse();
  }

  @Test
  void deletesAssetGrantsOnTheSystemLibrary() throws Exception {
    UUID owner = insertUser(UUID.randomUUID());
    UUID grant = insertAssetGrant(SYSTEM_LIBRARY_ID, owner);

    applyChangelog031();

    assertThat(assetGrantExists(grant)).isFalse();
  }

  @Test
  void deletesVectorStoreChunksBelongingToASystemLibraryDocument() throws Exception {
    createVectorStoreTable();
    UUID otherLibrary = insertLibrary(UUID.randomUUID(), insertUser(UUID.randomUUID()));
    UUID systemDocument = insertDocument(SYSTEM_LIBRARY_ID);
    UUID otherDocument = insertDocument(otherLibrary.toString());
    UUID systemChunk = insertChunk(systemDocument);
    UUID otherChunk = insertChunk(otherDocument);

    applyChangelog031();

    assertThat(chunkExists(systemChunk)).isFalse();
    assertThat(chunkExists(otherChunk)).isTrue();
  }

  @Test
  void deletesAnOrphanedVectorStoreChunkWhoseDocumentRowIsAlreadyGone() throws Exception {
    // A chunk that carries library_id in its own metadata (written by storeChunks, migration 016)
    // but whose document row no longer exists - the join alone would miss it, leaving a chunk that
    // still resolves to the system library in similarity search results forever.
    createVectorStoreTable();
    UUID orphanChunk = insertOrphanedChunkWithLibraryId(SYSTEM_LIBRARY_ID);

    applyChangelog031();

    assertThat(chunkExists(orphanChunk)).isFalse();
  }

  @Test
  void narrowsTheOwnerCheckConstraintsToRejectSystem() throws Exception {
    applyChangelog031();

    // Both chk_knowledge_libraries_owner_type and chk_knowledge_libraries_owner reject a 'SYSTEM'
    // row with no owner columns after this migration narrows them - Postgres does not guarantee
    // which of two simultaneously violated CHECK constraints is reported (the same caveat
    // Migration027LibrarySourceTypeAndConfigurationTest notes for its own multi-constraint case),
    // so this asserts on the shared "chk_knowledge_libraries_owner" name prefix both share rather
    // than pinning one specific constraint.
    assertThatThrownBy(() -> insertLibraryWithOwnerType(UUID.randomUUID(), "SYSTEM", null, null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_owner");
  }

  @Test
  void aFreshInstallationWithoutTheVectorStoreTableIsNotBlocked() throws Exception {
    // Liquibase runs before Spring AI creates vector_store, so on a fresh database the table is
    // absent at this point. Without the precondition, the first changeSet would fail the whole
    // startup.
    applyChangelog031();

    assertThat(libraryExists(SYSTEM_LIBRARY_ID)).isFalse();
    assertThat(changeSetIsRecorded("031-delete-system-library-vector-store-chunks")).isTrue();
  }

  private void applyChangelog031() throws Exception {
    applyChangelog(connection, "db/changelog/changes/031-delete-system-library.yaml");
  }

  private void createVectorStoreTable() throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE vector_store ("
              + "id uuid PRIMARY KEY, content text, metadata json, embedding vector(3))");
    }
  }

  private UUID insertChunk(UUID documentId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO vector_store (id, content, metadata) VALUES ('"
              + id
              + "', 'Ein Textabschnitt.', '{\"document_id\": \""
              + documentId
              + "\"}'::json)");
    }
    return id;
  }

  private UUID insertOrphanedChunkWithLibraryId(String libraryId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO vector_store (id, content, metadata) VALUES ('"
              + id
              + "', 'Ein verwaister Textabschnitt.', '{\"document_id\": \""
              + UUID.randomUUID()
              + "\", \"library_id\": \""
              + libraryId
              + "\"}'::json)");
    }
    return id;
  }

  private boolean chunkExists(UUID id) throws SQLException {
    return exists("vector_store", id.toString());
  }

  private UUID insertSystemOwnedLibrary() throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO knowledge_libraries "
              + "(id, organization_id, name, owner_type, owner_user_id, owner_group_id,"
              + " visibility, listed, personal, source_type, created_at, updated_at) VALUES ('"
              + id
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', 'Streunende SYSTEM-Bibliothek "
              + id
              + "', 'SYSTEM', NULL, NULL, 'PRIVATE', false, false, 'UPLOAD', now(), now())");
    }
    return id;
  }

  private UUID insertLibrary(UUID id, UUID ownerUserId) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO knowledge_libraries "
              + "(id, organization_id, name, owner_type, owner_user_id, owner_group_id,"
              + " visibility, listed, personal, source_type, created_at, updated_at) VALUES ('"
              + id
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', 'Andere Bibliothek "
              + id
              + "', 'USER', '"
              + ownerUserId
              + "', NULL, 'PRIVATE', false, false, 'UPLOAD', now(), now())");
    }
    return id;
  }

  /**
   * Inserts a library row with an arbitrary owner_type, bypassing this class's other, valid
   * factories - used only to prove the narrowed CHECK constraints reject 'SYSTEM' after the
   * migration has run (see {@link #narrowsTheOwnerCheckConstraintsToRejectSystem}).
   */
  private UUID insertLibraryWithOwnerType(
      UUID id, String ownerType, UUID ownerUserId, UUID ownerGroupId) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO knowledge_libraries "
              + "(id, organization_id, name, owner_type, owner_user_id, owner_group_id,"
              + " visibility, listed, personal, source_type, created_at, updated_at) VALUES ('"
              + id
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', 'Verbotener Versuch "
              + id
              + "', '"
              + ownerType
              + "', "
              + (ownerUserId == null ? "NULL" : "'" + ownerUserId + "'")
              + ", "
              + (ownerGroupId == null ? "NULL" : "'" + ownerGroupId + "'")
              + ", 'PRIVATE', false, false, 'UPLOAD', now(), now())");
    }
    return id;
  }

  private UUID insertDocument(String libraryId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO documents (id, file_name, file_path, status, source_type, library_id,"
              + " organization_id) VALUES ('"
              + id
              + "', 'a.pdf', '/tmp/a.pdf', 'INDEXED', 'FILESYSTEM', '"
              + libraryId
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "')");
    }
    return id;
  }

  private boolean documentExists(UUID id) throws SQLException {
    return exists("documents", id.toString());
  }

  private UUID insertIndexingJob(String libraryId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO indexing_jobs (id, status, started_at, library_id) VALUES ('"
              + id
              + "', 'COMPLETED', now(), '"
              + libraryId
              + "')");
    }
    return id;
  }

  private boolean indexingJobExists(UUID id) throws SQLException {
    return exists("indexing_jobs", id.toString());
  }

  private UUID insertUser(UUID id) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO users (id, subject, issuer, system_role, organization_id, created_at) "
              + "VALUES ('"
              + id
              + "', '"
              + id
              + "', 'test-issuer', 'USER', '"
              + SEEDED_ORGANIZATION_ID
              + "', now())");
    }
    return id;
  }

  private UUID insertAssetGrant(String libraryId, UUID subjectUserId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO asset_grants (id, library_id, organization_id, subject_type,"
              + " subject_user_id, role, created_at, updated_at) VALUES ('"
              + id
              + "', '"
              + libraryId
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', 'USER', '"
              + subjectUserId
              + "', 'VIEWER', now(), now())");
    }
    return id;
  }

  private boolean assetGrantExists(UUID id) throws SQLException {
    return exists("asset_grants", id.toString());
  }

  private boolean libraryExists(String id) throws SQLException {
    return exists("knowledge_libraries", id);
  }

  private boolean exists(String table, String id) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery("SELECT count(*) FROM " + table + " WHERE id = '" + id + "'")) {
      result.next();
      return result.getInt(1) > 0;
    }
  }

  private boolean changeSetIsRecorded(String changeSetId) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM databasechangelog WHERE id = '" + changeSetId + "'")) {
      result.next();
      return result.getInt(1) == 1;
    }
  }
}
