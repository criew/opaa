package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

/**
 * Applies Liquibase changelog 049 in isolation against a database built from {@code
 * test-master-through-047.yaml} - the real changelog through changeSet 047 (#678, merged), the same
 * pattern as {@code Migration046GroupsParentGroupOrganizationBindingTest} and {@code
 * Migration047UserReferencesOrganizationBindingTest}. 048 (#680) is not yet merged at the time this
 * class was rebased onto that fixture (#401 review follow-up, 20.08.2026) - see the fixture's own
 * comment for the rebase note once it lands.
 *
 * <p><b>#401: reproduces the bug at the schema level before proving the fix.</b> {@link
 * #beforeTheMigrationIndexingJobsCarriesNoOrganizationIdAtAll} runs against the pre-migration
 * ({@code through-047}) fixture alone, without applying 049 - the exact defect the issue describes:
 * {@code indexing_jobs} has no {@code organization_id} column at all, so nothing on the database
 * (or application) side can attribute a run to an organization, and a composite foreign key on
 * {@code library_id} referencing {@code knowledge_libraries(id, organization_id)} is not even
 * expressible. Every other test in this class applies 049 and proves the fixed behavior.
 */
class Migration049BindIndexingJobsToOrganizationTest extends AbstractMigrationTest {

  private static final String ORGANIZATION_A = "00000000-0000-0000-0000-000000000001";
  private static final String ORGANIZATION_B = "00000000-0000-0000-0000-000000000002";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-047.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    // ORGANIZATION_A is migration 008's own seeded default organization id - insertOrganization is
    // idempotent (ON CONFLICT DO NOTHING) so re-inserting it here is harmless. ORGANIZATION_B is
    // deliberately *not* inserted here: 049-backfill-indexing-jobs-organization-id's own
    // preConditions HALTs unless exactly one organization exists at the moment it runs (#401
    // review, Nit 4), so any test that needs a second organization inserts it itself, strictly
    // after applyChangelog049() has already run.
    insertOrganization(ORGANIZATION_A);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void beforeTheMigrationIndexingJobsCarriesNoOrganizationIdAtAll() throws Exception {
    // Deliberately does *not* call applyChangelog049() - proves the bug #401 describes exists in
    // the schema exactly as migration 019 left it: indexing_jobs has no organization_id column, so
    // a run cannot be attributed to an organization at all.
    assertThat(columnExists("indexing_jobs", "organization_id")).isFalse();
  }

  @Test
  void afterTheMigrationEveryRowHasTheOrganizationOfItsTargetLibrary() throws Exception {
    UUID libraryInOrganizationA = insertLibrary(ORGANIZATION_A);
    UUID jobId = insertJobPreMigration(libraryInOrganizationA);

    applyChangelog049();

    assertThat(columnValue("indexing_jobs", "organization_id", jobId)).isEqualTo(ORGANIZATION_A);
  }

  @Test
  void afterTheMigrationARowWithoutALibraryFallsBackToTheSingleSeededOrganization()
      throws Exception {
    // #401 maintainer comment (20.08.2026): migration 003's original rows never had a library_id
    // at all, and a library's own deletion nulls it out too (ON DELETE SET NULL, migration 019) -
    // both cases must still end up attributed to *some* organization, not left NULL.
    UUID jobId = insertJobPreMigration(null);

    applyChangelog049();

    assertThat(columnValue("indexing_jobs", "organization_id", jobId)).isEqualTo(ORGANIZATION_A);
  }

  @Test
  void afterTheMigrationOrganizationIdIsNotNull() throws Exception {
    applyChangelog049();

    assertThatThrownBy(() -> insertJobPostMigration(UUID.randomUUID().toString(), null, null))
        .isInstanceOf(SQLException.class);
  }

  @Test
  void afterTheMigrationOrganizationIdMustReferenceARealOrganization() throws Exception {
    applyChangelog049();
    UUID unknownOrganization = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                insertJobPostMigration(
                    UUID.randomUUID().toString(), null, unknownOrganization.toString()))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_indexing_jobs_organization");
  }

  @Test
  void afterTheMigrationAJobCannotNameALibraryFromAnotherOrganization() throws Exception {
    applyChangelog049();
    // Inserted only now, after the migration (and its single-organization-backfill precondition)
    // has already run - see setUp()'s own comment.
    insertOrganization(ORGANIZATION_B);
    UUID libraryInOrganizationB = insertLibrary(ORGANIZATION_B);

    // The composite foreign key fk_indexing_jobs_library_organization references
    // knowledge_libraries(id, organization_id) - a job naming this library while itself carrying
    // organization A must violate it, because no such (id, organization_id) pair exists.
    assertThatThrownBy(
            () ->
                insertJobPostMigration(
                    UUID.randomUUID().toString(),
                    libraryInOrganizationB.toString(),
                    ORGANIZATION_A))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_indexing_jobs_library_organization");
  }

  @Test
  void afterTheMigrationAJobCanStillNameALibraryFromTheSameOrganization() throws Exception {
    applyChangelog049();
    UUID library = insertLibrary(ORGANIZATION_A);
    UUID jobId = UUID.randomUUID();

    insertJobPostMigration(jobId.toString(), library.toString(), ORGANIZATION_A);

    assertThat(columnValue("indexing_jobs", "library_id", jobId)).isEqualTo(library.toString());
  }

  @Test
  void deletingALibraryNullsOnlyTheJobsLibraryIdNotItsOrganizationId() throws Exception {
    applyChangelog049();
    UUID library = insertLibrary(ORGANIZATION_A);
    UUID jobId = UUID.randomUUID();
    insertJobPostMigration(jobId.toString(), library.toString(), ORGANIZATION_A);

    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM knowledge_libraries WHERE id = '" + library + "'");
    }

    // Postgres 15+ column-list ON DELETE SET NULL (library_id) - the composite key's other column,
    // organization_id, is NOT NULL and belongs to the job row itself, so it must survive its
    // library's deletion untouched (mirrors migration 046's identical guarantee for groups).
    assertThat(columnValue("indexing_jobs", "library_id", jobId)).isNull();
    assertThat(columnValue("indexing_jobs", "organization_id", jobId)).isEqualTo(ORGANIZATION_A);
  }

  @Test
  void rollbackRestoresTheSingleColumnLibraryForeignKeyAndDropsOrganizationId() throws Exception {
    applyChangelog049();

    rollbackChangelog049();

    assertThat(columnExists("indexing_jobs", "organization_id")).isFalse();

    // The single-column fk_indexing_jobs_library (migration 019) is restored: a library_id naming
    // a library that does not exist at all must still be rejected by *some* foreign key.
    UUID nonExistentLibraryId = UUID.randomUUID();
    assertThatThrownBy(
            () -> insertJobPreMigrationWithLibrary(UUID.randomUUID(), nonExistentLibraryId))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_indexing_jobs_library");
  }

  private void applyChangelog049() throws Exception {
    applyChangelog(connection, "db/changelog/changes/049-bind-indexing-jobs-to-organization.yaml");
  }

  private void rollbackChangelog049() throws Exception {
    Liquibase liquibase =
        new Liquibase(
            "db/changelog/changes/049-bind-indexing-jobs-to-organization.yaml",
            new ClassLoaderResourceAccessor(),
            liquibaseDatabase(connection));
    liquibase.rollback(4, (String) null);
    connection.setAutoCommit(true);
  }

  private void insertOrganization(String id) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO organizations (id, name, created_at) VALUES ('"
              + id
              + "', 'Org "
              + id
              + "', now()) ON CONFLICT (id) DO NOTHING");
    }
  }

  private UUID insertLibrary(String organizationId) throws SQLException {
    UUID id = UUID.randomUUID();
    UUID ownerId = insertUser(organizationId);
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type, owner_user_id,"
              + " visibility, source_type, created_at, updated_at) VALUES ('"
              + id
              + "', '"
              + organizationId
              + "', 'Bibliothek "
              + id
              + "', 'USER', '"
              + ownerId
              + "', 'PRIVATE', 'UPLOAD', now(), now())");
    }
    return id;
  }

  private UUID insertUser(String organizationId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
              + " organization_id) VALUES ('"
              + id
              + "', '"
              + id
              + "', 'opaa-test', '"
              + id
              + "@example.com', 'Test-Nutzer', now(), 'USER', '"
              + organizationId
              + "')");
    }
    return id;
  }

  /** Pre-migration schema: indexing_jobs has no organization_id column yet. */
  private UUID insertJobPreMigration(UUID libraryId) throws SQLException {
    return insertJobPreMigrationWithLibrary(UUID.randomUUID(), libraryId);
  }

  private UUID insertJobPreMigrationWithLibrary(UUID jobId, UUID libraryId) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO indexing_jobs (id, status, started_at, last_progress_at, library_id)"
              + " VALUES ('"
              + jobId
              + "', 'RUNNING', now(), now(), "
              + (libraryId == null ? "NULL" : "'" + libraryId + "'")
              + ")");
    }
    return jobId;
  }

  /**
   * Post-migration schema: indexing_jobs has organization_id. {@code jobId}/{@code libraryId}/
   * {@code organizationId} are passed as strings (not parsed to {@link UUID}) so the caller can
   * also exercise a {@code null} organizationId, which a {@link UUID} field could not represent.
   */
  private void insertJobPostMigration(String jobId, String libraryId, String organizationId)
      throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO indexing_jobs (id, status, started_at, last_progress_at, library_id,"
              + " organization_id) VALUES ('"
              + jobId
              + "', 'RUNNING', now(), now(), "
              + (libraryId == null ? "NULL" : "'" + libraryId + "'")
              + ", "
              + (organizationId == null ? "NULL" : "'" + organizationId + "'")
              + ")");
    }
  }

  private boolean columnExists(String table, String column) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT column_name FROM information_schema.columns WHERE table_name = '"
                    + table
                    + "' AND column_name = '"
                    + column
                    + "'")) {
      return result.next();
    }
  }

  private String columnValue(String table, String column, UUID id) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT " + column + " FROM " + table + " WHERE id = '" + id + "'")) {
      result.next();
      return result.getString(1);
    }
  }
}
