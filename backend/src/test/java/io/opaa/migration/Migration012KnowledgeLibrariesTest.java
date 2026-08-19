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
 * Applies Liquibase changelog 012 in isolation against a database built from the real, versioned
 * changelog through changeSet 011 - the same pattern as {@code Migration011DirectorySyncTest}, with
 * {@code test-master-through-011.yaml} as the pre-migration fixture, now built once per class into
 * a template database and cloned per test method ({@link AbstractMigrationTest}).
 *
 * <p>The two mechanisms this test class exercises against each other, not just individually: {@link
 * #backfillOnlyTouchesRowsStillMissingALibraryAcrossASecondUpdateCall()} combines a second, real
 * {@code liquibase.update()} call with rows the backfill's {@code WHERE library_id IS NULL}
 * predicate must skip, and {@link
 * #compositeForeignKeyRejectsADocumentPointingAtALibraryFromAnotherOrganization()} combines the
 * composite foreign key with a cross-organization library to prove the constraint - not just
 * application code - is what stops the leak.
 */
class Migration012KnowledgeLibrariesTest extends AbstractMigrationTest {

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";
  private static final String SYSTEM_LIBRARY_ID = "00000000-0000-0000-0000-000000000002";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-011.yaml";
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
  void createsSystemLibraryReadableOnlyByDesignFailClosed() throws Exception {
    applyChangelog012();

    assertThat(columnValue("knowledge_libraries", "owner_type", SYSTEM_LIBRARY_ID))
        .isEqualTo("SYSTEM");
    assertThat(columnValueOrNull("knowledge_libraries", "owner_user_id", SYSTEM_LIBRARY_ID))
        .isNull();
    assertThat(columnValueOrNull("knowledge_libraries", "owner_group_id", SYSTEM_LIBRARY_ID))
        .isNull();
    assertThat(columnValue("knowledge_libraries", "visibility", SYSTEM_LIBRARY_ID))
        .isEqualTo("PRIVATE");
    assertThat(columnValue("knowledge_libraries", "listed", SYSTEM_LIBRARY_ID)).isEqualTo("f");
    assertThat(columnValue("knowledge_libraries", "personal", SYSTEM_LIBRARY_ID)).isEqualTo("f");
  }

  @Test
  void ownerCheckConstraintRejectsEveryMismatchBetweenOwnerTypeAndOwnerColumns() throws Exception {
    applyChangelog012();
    UUID someUser = insertUser(UUID.randomUUID());
    UUID someGroup = insertGroup(UUID.randomUUID());

    // USER without owner_user_id.
    assertThatThrownBy(
            () -> insertLibrary(UUID.randomUUID(), "USER", null, null, "PRIVATE", false, false))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_owner");

    // USER with owner_group_id set instead of owner_user_id.
    assertThatThrownBy(
            () ->
                insertLibrary(UUID.randomUUID(), "USER", null, someGroup, "PRIVATE", false, false))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_owner");

    // GROUP without owner_group_id.
    assertThatThrownBy(
            () -> insertLibrary(UUID.randomUUID(), "GROUP", null, null, "PRIVATE", false, false))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_owner");

    // SYSTEM with an owner_user_id set.
    assertThatThrownBy(
            () ->
                insertLibrary(UUID.randomUUID(), "SYSTEM", someUser, null, "PRIVATE", false, false))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_owner");

    // SYSTEM with an owner_group_id set.
    assertThatThrownBy(
            () ->
                insertLibrary(
                    UUID.randomUUID(), "SYSTEM", null, someGroup, "PRIVATE", false, false))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_owner");
  }

  @Test
  void foreignKeysRejectAnOwnerUserOrOwnerGroupThatDoesNotExist() throws Exception {
    applyChangelog012();

    assertThatThrownBy(
            () ->
                insertLibrary(
                    UUID.randomUUID(), "USER", UUID.randomUUID(), null, "PRIVATE", false, false))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_knowledge_libraries_owner_user");

    assertThatThrownBy(
            () ->
                insertLibrary(
                    UUID.randomUUID(), "GROUP", null, UUID.randomUUID(), "PRIVATE", false, false))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_knowledge_libraries_owner_group_organization");
  }

  @Test
  void partialUniqueIndexAllowsOnlyOnePersonalLibraryPerOwnerButAnyNumberOfNonPersonalOnes()
      throws Exception {
    applyChangelog012();
    UUID owner = insertUser(UUID.randomUUID());

    insertLibrary(UUID.randomUUID(), "USER", owner, null, "PRIVATE", false, true);

    assertThatThrownBy(
            () -> insertLibrary(UUID.randomUUID(), "USER", owner, null, "PRIVATE", false, true))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_knowledge_libraries_personal_owner");

    // A user may still own any number of non-personal libraries - the partial index must not
    // restrict that, mirroring uk_spaces_personal_owner's guarantee for spaces (migration 010).
    insertLibrary(UUID.randomUUID(), "USER", owner, null, "SHARED", true, false);
    insertLibrary(UUID.randomUUID(), "USER", owner, null, "ORGANIZATION", true, false);
  }

  @Test
  void backfillAssignsEveryPreExistingDocumentToTheSystemLibraryWithoutLosingRows()
      throws Exception {
    insertDocument(UUID.randomUUID(), "a.pdf");
    insertDocument(UUID.randomUUID(), "b.pdf");
    insertDocument(UUID.randomUUID(), "c.pdf");
    assertThat(countRows("documents")).isEqualTo(3);

    applyChangelog012();

    assertThat(countRows("documents")).isEqualTo(3);
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery("SELECT library_id, organization_id FROM documents")) {
      int rows = 0;
      while (rs.next()) {
        rows++;
        assertThat(rs.getString("library_id")).isEqualTo(SYSTEM_LIBRARY_ID);
        assertThat(rs.getString("organization_id")).isEqualTo(SEEDED_ORGANIZATION_ID);
      }
      assertThat(rows).isEqualTo(3);
    }
  }

  @Test
  void backfillOnlyTouchesRowsStillMissingALibraryAcrossASecondUpdateCall() throws Exception {
    // This does NOT test resumability within an interrupted transaction - the backfill changeSet
    // is a single UPDATE inside one changeSet transaction (runInTransaction: true, Liquibase's
    // default); a run that fails partway through rolls back entirely and is never partially
    // applied (see the changeSet's own comment and the Resumierbarkeit section of
    // docs/migrations/012-knowledge-library.md for why an earlier, batched version of this
    // changeSet claimed otherwise and was wrong). What this test actually pins: the backfill's
    // WHERE library_id IS NULL predicate only touches rows that still need it, so a second
    // liquibase.update() call - the real DATABASECHANGELOG-level resumability this migration
    // provides - does not fail or duplicate work if some rows were already assigned by other
    // means (e.g. application code) between the two calls.
    applySchemaOnlyChangelog012();

    UUID alreadyAssigned = UUID.randomUUID();
    UUID stillPending1 = UUID.randomUUID();
    UUID stillPending2 = UUID.randomUUID();
    insertDocument(alreadyAssigned, "already.pdf");
    insertDocument(stillPending1, "pending1.pdf");
    insertDocument(stillPending2, "pending2.pdf");
    // Assign one document by hand before the backfill changeSet ever runs - the
    // knowledge_libraries table and the nullable columns already exist at this point (both are
    // part of the lib-schema context applied above), so this update is legal.
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "UPDATE documents SET library_id = '"
              + SYSTEM_LIBRARY_ID
              + "', organization_id = '"
              + SEEDED_ORGANIZATION_ID
              + "' WHERE id = '"
              + alreadyAssigned
              + "'");
    }

    // Apply the still-pending changeSets (backfill + enforcement) as a second, real
    // liquibase.update() call.
    resumeChangelog012();

    assertThat(countRows("documents")).isEqualTo(3);
    for (UUID id : new UUID[] {alreadyAssigned, stillPending1, stillPending2}) {
      assertThat(columnValue("documents", "library_id", id.toString()))
          .isEqualTo(SYSTEM_LIBRARY_ID);
      assertThat(columnValue("documents", "organization_id", id.toString()))
          .isEqualTo(SEEDED_ORGANIZATION_ID);
    }
  }

  @Test
  void enforcesNotNullAndCompositeForeignKeyOnDocumentsAfterBackfill() throws Exception {
    applyChangelog012();

    assertThatThrownBy(
            () ->
                insertDocumentWithExplicitLibrary(
                    UUID.randomUUID(), "no-library.pdf", null, SEEDED_ORGANIZATION_ID))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("library_id");
  }

  @Test
  void compositeForeignKeyRejectsADocumentPointingAtALibraryFromAnotherOrganization()
      throws Exception {
    applyChangelog012();

    UUID otherOrganization = UUID.randomUUID();
    insertOrganization(otherOrganization);
    UUID otherOrgOwner = insertUser(UUID.randomUUID(), otherOrganization.toString());
    UUID libraryInOtherOrganization = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO knowledge_libraries "
              + "(id, organization_id, name, owner_type, owner_user_id, visibility, listed,"
              + " personal, created_at, updated_at) VALUES ('"
              + libraryInOtherOrganization
              + "', '"
              + otherOrganization
              + "', 'Andere Organisation', 'USER', '"
              + otherOrgOwner
              + "', 'PRIVATE', false, false, now(), now())");
    }

    // library_id points at a real library, but organization_id on the document does not match
    // that library's organization_id - the exact cross-tenant mismatch
    // fk_documents_library_organization exists to reject at the database level, not just in
    // application code.
    assertThatThrownBy(
            () ->
                insertDocumentWithExplicitLibrary(
                    UUID.randomUUID(),
                    "cross-org.pdf",
                    libraryInOtherOrganization.toString(),
                    SEEDED_ORGANIZATION_ID))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_documents_library_organization");
  }

  private void applyChangelog012() throws Exception {
    applyChangelog(connection, "db/changelog/changes/012-knowledge-libraries.yaml");
  }

  /**
   * Applies only the {@code lib-schema}-context changeSets (table creation, system library seed,
   * nullable column addition) - simulates a migration run interrupted before the backfill. See the
   * context labels documented at the top of 012-knowledge-libraries.yaml.
   */
  private void applySchemaOnlyChangelog012() throws Exception {
    applyChangelog(connection, "db/changelog/changes/012-knowledge-libraries.yaml", "lib-schema");
  }

  /**
   * Applies every changeSet not yet recorded in DATABASECHANGELOG - an empty {@link
   * liquibase.Contexts} matches all changeSets regardless of their {@code context} attribute, so
   * this resumes exactly where {@link #applySchemaOnlyChangelog012()} left off (the schema
   * changeSets are skipped as already-applied; only the backfill and enforcement changeSets
   * actually run).
   */
  private void resumeChangelog012() throws Exception {
    applyChangelog012();
  }

  private void insertOrganization(UUID id) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO organizations (id, name, created_at) VALUES ('"
              + id
              + "', 'Org "
              + id
              + "', now()) ON CONFLICT (id) DO NOTHING");
    }
  }

  private void insertLibrary(
      UUID id,
      String ownerType,
      UUID ownerUserId,
      UUID ownerGroupId,
      String visibility,
      boolean listed,
      boolean personal)
      throws SQLException {
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
              + "', '"
              + ownerType
              + "', "
              + (ownerUserId == null ? "NULL" : "'" + ownerUserId + "'")
              + ", "
              + (ownerGroupId == null ? "NULL" : "'" + ownerGroupId + "'")
              + ", '"
              + visibility
              + "', "
              + listed
              + ", "
              + personal
              + ", now(), now())");
    }
  }

  private UUID insertUser(UUID id) throws SQLException {
    return insertUser(id, SEEDED_ORGANIZATION_ID);
  }

  private UUID insertUser(UUID id, String organizationId) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO users (id, subject, issuer, system_role, organization_id, created_at) "
              + "VALUES ('"
              + id
              + "', '"
              + id
              + "', 'test-issuer', 'USER', '"
              + organizationId
              + "', now())");
    }
    return id;
  }

  private UUID insertGroup(UUID id) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO groups (id, organization_id, kind, name, created_at, updated_at) "
              + "VALUES ('"
              + id
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', 'AD_HOC', 'Gruppe "
              + id
              + "', now(), now())");
    }
    return id;
  }

  private void insertDocument(UUID id, String fileName) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO documents (id, file_name, file_path, status, source_type) VALUES ('"
              + id
              + "', '"
              + fileName
              + "', '/tmp/"
              + fileName
              + "', 'INDEXED', 'FILESYSTEM')");
    }
  }

  private void insertDocumentWithExplicitLibrary(
      UUID id, String fileName, String libraryId, String organizationId) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO documents (id, file_name, file_path, status, source_type, library_id,"
              + " organization_id) VALUES ('"
              + id
              + "', '"
              + fileName
              + "', '/tmp/"
              + fileName
              + "', 'INDEXED', 'FILESYSTEM', "
              + (libraryId == null ? "NULL" : "'" + libraryId + "'")
              + ", "
              + (organizationId == null ? "NULL" : "'" + organizationId + "'")
              + ")");
    }
  }

  private long countRows(String table) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private String columnValue(String table, String column, String id) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT " + column + " FROM " + table + " WHERE id = '" + id + "'")) {
      rs.next();
      return rs.getString(1);
    }
  }

  private String columnValueOrNull(String table, String column, String id) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT " + column + " FROM " + table + " WHERE id = '" + id + "'")) {
      rs.next();
      String value = rs.getString(1);
      return rs.wasNull() ? null : value;
    }
  }
}
