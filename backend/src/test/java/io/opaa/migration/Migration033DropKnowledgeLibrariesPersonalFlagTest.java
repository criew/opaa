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

/**
 * Applies Liquibase changelog 033 in isolation against a database built from the real, versioned
 * changelog through changeSet 032 - the same pattern as {@code
 * Migration031DeleteSystemLibraryTest}, with {@code test-master-through-032.yaml} as the
 * pre-migration fixture (see that fixture's own comment).
 *
 * <p>#522: the {@code personal} column and its backing partial unique index ({@code
 * uk_knowledge_libraries_personal_owner}, migration 012) are dropped from {@code
 * knowledge_libraries} - the automatic personal upload library and its categorisation no longer
 * exist in application code. A library the now-deleted automation already created before this
 * migration runs is deliberately left otherwise untouched: it keeps its existing owner {@code
 * OWNER} grant (written by the automation at creation time) and simply becomes an ordinary
 * user-owned library once the flag that used to mark it is gone - see {@link
 * #anExistingAutomaticallyCreatedLibraryKeepsItsRowAndOwnerGrant()}.
 */
class Migration033DropKnowledgeLibrariesPersonalFlagTest extends AbstractMigrationTest {

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-032.yaml";
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
  void dropsThePersonalColumn() throws Exception {
    assertThat(hasPersonalColumn()).isTrue();

    applyChangelog033();

    assertThat(hasPersonalColumn()).isFalse();
  }

  @Test
  void dropsThePersonalOwnerPartialUniqueIndex() throws Exception {
    assertThat(hasPersonalOwnerIndex()).isTrue();

    applyChangelog033();

    assertThat(hasPersonalOwnerIndex()).isFalse();
  }

  @Test
  void anExistingAutomaticallyCreatedLibraryKeepsItsRowAndOwnerGrant() throws Exception {
    UUID owner = insertUser(UUID.randomUUID());
    UUID libraryId = insertPersonalLibrary(owner);
    UUID grantId = insertOwnerGrant(libraryId, owner);

    applyChangelog033();

    assertThat(libraryExists(libraryId)).isTrue();
    assertThat(assetGrantExists(grantId)).isTrue();
  }

  @Test
  void aNonPersonalLibraryIsUnaffected() throws Exception {
    UUID owner = insertUser(UUID.randomUUID());
    UUID libraryId = insertLibrary(owner, false);

    applyChangelog033();

    assertThat(libraryExists(libraryId)).isTrue();
  }

  @Test
  void rollbackRestoresTheColumnAndTheIndex() throws Exception {
    applyChangelog033();
    assertThat(hasPersonalColumn()).isFalse();
    assertThat(hasPersonalOwnerIndex()).isFalse();

    Liquibase liquibase =
        new Liquibase(
            "db/changelog/changes/033-drop-knowledge-libraries-personal-flag.yaml",
            new ClassLoaderResourceAccessor(),
            liquibaseDatabase(connection));
    liquibase.rollback(2, (String) null);
    connection.setAutoCommit(true);

    assertThat(hasPersonalColumn()).isTrue();
    assertThat(hasPersonalOwnerIndex()).isTrue();
  }

  private void applyChangelog033() throws Exception {
    applyChangelog(
        connection, "db/changelog/changes/033-drop-knowledge-libraries-personal-flag.yaml");
  }

  private boolean hasPersonalColumn() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM information_schema.columns"
                    + " WHERE table_name = 'knowledge_libraries' AND column_name = 'personal'")) {
      result.next();
      return result.getInt(1) == 1;
    }
  }

  private boolean hasPersonalOwnerIndex() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'knowledge_libraries' AND"
                    + " indexname = 'uk_knowledge_libraries_personal_owner'")) {
      result.next();
      return result.getInt(1) == 1;
    }
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

  private UUID insertPersonalLibrary(UUID ownerUserId) throws SQLException {
    return insertLibrary(ownerUserId, true);
  }

  private UUID insertLibrary(UUID ownerUserId, boolean personal) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO knowledge_libraries "
              + "(id, organization_id, name, description, owner_type, owner_user_id, visibility,"
              + " listed, personal, source_type, created_at, updated_at) VALUES ('"
              + id
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', 'Meine Dokumente "
              + id
              + "', 'Private persoenliche Wissensbibliothek', 'USER', '"
              + ownerUserId
              + "', 'PRIVATE', false, "
              + personal
              + ", 'UPLOAD', now(), now())");
    }
    return id;
  }

  private UUID insertOwnerGrant(UUID libraryId, UUID ownerUserId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO asset_grants (id, library_id, organization_id, subject_type,"
              + " subject_user_id, role, granted_by_user_id, created_at, updated_at) VALUES ('"
              + id
              + "', '"
              + libraryId
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', 'USER', '"
              + ownerUserId
              + "', 'OWNER', '"
              + ownerUserId
              + "', now(), now())");
    }
    return id;
  }

  private boolean libraryExists(UUID id) throws SQLException {
    return exists("knowledge_libraries", id.toString());
  }

  private boolean assetGrantExists(UUID id) throws SQLException {
    return exists("asset_grants", id.toString());
  }

  private boolean exists(String table, String id) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery("SELECT count(*) FROM " + table + " WHERE id = '" + id + "'")) {
      result.next();
      return result.getInt(1) > 0;
    }
  }
}
