package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Applies Liquibase changelog 019 in isolation against a database built from the real, versioned
 * changelog through changeSet 016 - the same pattern as {@code
 * Migration016VectorStoreLibraryMetadataTest}, with {@code test-master-through-016.yaml} as the
 * pre-migration fixture (see that fixture's own comment for why it stops at 016, not 017/018).
 *
 * <p>Covers what PR #431 review nit 1 found untested: the {@code ON DELETE SET NULL} behavior of
 * {@code fk_indexing_jobs_library} - deliberately not {@code RESTRICT} like {@code
 * fk_documents_library_organization}, because {@code indexing_jobs} is a historical record of past
 * runs, not a live reference whose target must always exist (see {@link
 * io.opaa.indexing.IndexingJob#getLibraryId()}'s Javadoc) - and the rollback block.
 */
@Testcontainers(disabledWithoutDocker = true)
class Migration019IndexingJobLibraryTest extends AbstractMigrationTest {

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-016.yaml";
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
  void addsALibraryIdColumnReferencingKnowledgeLibraries() throws Exception {
    UUID libraryId = insertSystemLibrary();
    UUID jobId = insertIndexingJob();

    applyChangelog019();

    setJobLibrary(jobId, libraryId);
    assertThat(jobLibraryId(jobId)).isEqualTo(libraryId.toString());
  }

  @Test
  void deletingTheLibrarySetsTheJobsLibraryIdToNullInsteadOfBlockingTheDelete() throws Exception {
    // The behavior this test exists for (PR #431 review, nit 1): indexing_jobs is a historical
    // record, not a live reference - deleting a library that a past run once targeted must not be
    // blocked by that history, unlike documents.library_id (RESTRICT, migration 012).
    UUID libraryId = insertSystemLibrary();
    UUID jobId = insertIndexingJob();
    applyChangelog019();
    setJobLibrary(jobId, libraryId);

    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM knowledge_libraries WHERE id = '" + libraryId + "'");
    }

    assertThat(jobLibraryId(jobId)).isNull();
  }

  @Test
  void rollbackDropsTheColumnAndTheConstraint() throws Exception {
    applyChangelog019();
    assertThat(hasLibraryIdColumn()).isTrue();

    Liquibase liquibase =
        new Liquibase(
            "db/changelog/changes/019-indexing-job-library.yaml",
            new ClassLoaderResourceAccessor(),
            liquibaseDatabase(connection));
    liquibase.rollback(1, (String) null);
    connection.setAutoCommit(true);

    assertThat(hasLibraryIdColumn()).isFalse();
  }

  private void applyChangelog019() throws Exception {
    applyChangelog(connection, "db/changelog/changes/019-indexing-job-library.yaml");
  }

  private UUID insertSystemLibrary() throws SQLException {
    UUID id = UUID.randomUUID();
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

  private UUID insertIndexingJob() throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO indexing_jobs (id, status, started_at) VALUES ('"
              + id
              + "', 'RUNNING', now())");
    }
    return id;
  }

  private void setJobLibrary(UUID jobId, UUID libraryId) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "UPDATE indexing_jobs SET library_id = '" + libraryId + "' WHERE id = '" + jobId + "'");
    }
  }

  private String jobLibraryId(UUID jobId) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT library_id FROM indexing_jobs WHERE id = '" + jobId + "'")) {
      result.next();
      return result.getString(1);
    }
  }

  private boolean hasLibraryIdColumn() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM information_schema.columns WHERE table_name ="
                    + " 'indexing_jobs' AND column_name = 'library_id'")) {
      result.next();
      return result.getInt(1) == 1;
    }
  }
}
