package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Applies Liquibase changelog 028 in isolation against a database built from the real, versioned
 * changelog through changeSet 016 - the same pattern {@code Migration019IndexingJobLibraryTest}
 * establishes (see that test's own Javadoc): {@code indexing_jobs.library_id} already exists by
 * then (changeSet 019), and {@code knowledge_libraries} by changeSet 012, well within the 016
 * fixture.
 *
 * <p>Covers what {@code IndexingJobServiceTest}'s mocked repository cannot: that {@code
 * uk_indexing_jobs_library_running} actually rejects a second concurrent RUNNING row for the same
 * library at the database level (#500 review, finding 3, the TOCTOU gap between {@code
 * IndexingJobService#isJobRunning} and {@code #startJob}), while still allowing multiple
 * non-RUNNING rows for the same library (its historical record) and RUNNING rows for different
 * libraries (#478's per-library concurrency) to coexist.
 */
@Testcontainers(disabledWithoutDocker = true)
class Migration028UniqueRunningIndexingJobPerLibraryTest {

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";

  private Connection connection;
  private Database database;

  @BeforeEach
  void setUp() throws Exception {
    connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    database =
        DatabaseFactory.getInstance()
            .findCorrectDatabaseImplementation(new JdbcConnection(connection));

    Liquibase liquibase =
        new Liquibase(
            "db/changelog/test-master-through-016.yaml",
            new ClassLoaderResourceAccessor(),
            database);
    liquibase.update(new Contexts());
    connection.setAutoCommit(true);

    applyChangelog019();
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.setAutoCommit(true);
    try (Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA public CASCADE");
      statement.execute("CREATE SCHEMA public");
    }
    connection.close();
  }

  @Test
  void rejectsASecondConcurrentRunningJobForTheSameLibrary() throws Exception {
    UUID libraryId = insertSystemLibrary();
    applyChangelog028();

    insertIndexingJob(libraryId, "RUNNING");

    assertThatThrownBy(() -> insertIndexingJob(libraryId, "RUNNING"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_indexing_jobs_library_running");
  }

  @Test
  void allowsRunningJobsForDifferentLibrariesAtTheSameTime() throws Exception {
    UUID firstLibrary = insertSystemLibrary();
    UUID secondLibrary = insertSystemLibrary();
    applyChangelog028();

    insertIndexingJob(firstLibrary, "RUNNING");
    insertIndexingJob(secondLibrary, "RUNNING");

    assertThat(countRunningJobs(firstLibrary)).isEqualTo(1);
    assertThat(countRunningJobs(secondLibrary)).isEqualTo(1);
  }

  @Test
  void allowsManyNonRunningJobsForTheSameLibrary() throws Exception {
    // indexing_jobs is a historical record - only concurrent RUNNING rows must be prevented, not
    // the accumulated COMPLETED/FAILED history of past runs.
    UUID libraryId = insertSystemLibrary();
    applyChangelog028();

    insertIndexingJob(libraryId, "COMPLETED");
    insertIndexingJob(libraryId, "COMPLETED");
    insertIndexingJob(libraryId, "FAILED");

    assertThat(countJobs(libraryId)).isEqualTo(3);
  }

  @Test
  void allowsARunningJobAfterAnEarlierRunForTheSameLibraryHasCompleted() throws Exception {
    UUID libraryId = insertSystemLibrary();
    applyChangelog028();

    insertIndexingJob(libraryId, "COMPLETED");
    insertIndexingJob(libraryId, "RUNNING");

    assertThat(countRunningJobs(libraryId)).isEqualTo(1);
  }

  @Test
  void rollbackDropsTheIndex() throws Exception {
    applyChangelog028();
    assertThat(hasUniqueRunningIndex()).isTrue();

    Liquibase liquibase =
        new Liquibase(
            "db/changelog/changes/028-unique-running-indexing-job-per-library.yaml",
            new ClassLoaderResourceAccessor(),
            database);
    liquibase.rollback(1, (String) null);
    connection.setAutoCommit(true);

    assertThat(hasUniqueRunningIndex()).isFalse();
  }

  private void applyChangelog019() throws Exception {
    Liquibase liquibase =
        new Liquibase(
            "db/changelog/changes/019-indexing-job-library.yaml",
            new ClassLoaderResourceAccessor(),
            database);
    liquibase.update(new Contexts());
    connection.setAutoCommit(true);
  }

  private void applyChangelog028() throws Exception {
    Liquibase liquibase =
        new Liquibase(
            "db/changelog/changes/028-unique-running-indexing-job-per-library.yaml",
            new ClassLoaderResourceAccessor(),
            database);
    liquibase.update(new Contexts());
    connection.setAutoCommit(true);
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

  private void insertIndexingJob(UUID libraryId, String status) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO indexing_jobs (id, status, started_at, library_id) VALUES ('"
              + id
              + "', '"
              + status
              + "', now(), '"
              + libraryId
              + "')");
    }
  }

  private int countRunningJobs(UUID libraryId) throws SQLException {
    return countJobsWithStatus(libraryId, "RUNNING");
  }

  private int countJobs(UUID libraryId) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM indexing_jobs WHERE library_id = '" + libraryId + "'")) {
      result.next();
      return result.getInt(1);
    }
  }

  private int countJobsWithStatus(UUID libraryId, String status) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM indexing_jobs WHERE library_id = '"
                    + libraryId
                    + "' AND status = '"
                    + status
                    + "'")) {
      result.next();
      return result.getInt(1);
    }
  }

  private boolean hasUniqueRunningIndex() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'indexing_jobs' AND indexname"
                    + " = 'uk_indexing_jobs_library_running'")) {
      result.next();
      return result.getInt(1) == 1;
    }
  }
}
