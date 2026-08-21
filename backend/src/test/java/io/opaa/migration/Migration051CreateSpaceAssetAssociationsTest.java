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
 * Applies Liquibase changelog 051 in isolation against a database built from {@code
 * test-master-through-050.yaml} - spaces, knowledge_libraries and users (including {@code
 * uk_users_id_organization}, added in migration 047) are all already in place by changeSet 050, and
 * this migration needs nothing else.
 *
 * <p>#203/#686: space_asset_associations is a brand-new table, so - unlike migrations 046-050,
 * which retrofit an existing single-column foreign key - both foreign keys are composite against
 * (id, organization_id) from the very first changeSet. {@link
 * #aCrossOrganizationAssociationIsRejected()} proves that holds; every other test proves the
 * ordinary behaviour (uniqueness, cascade-on-space-delete leaving the library untouched,
 * cascade-on-library-delete).
 */
class Migration051CreateSpaceAssetAssociationsTest extends AbstractMigrationTest {

  private static final String ORGANIZATION_A = "00000000-0000-0000-0000-000000000001";
  private static final String ORGANIZATION_B = "00000000-0000-0000-0000-000000000002";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-050.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    insertOrganization(ORGANIZATION_A);
    insertOrganization(ORGANIZATION_B);
    applyChangelog(connection, "db/changelog/changes/051-create-space-asset-associations.yaml");
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void anAssociationCanBeCreatedAndRead() throws SQLException {
    UUID owner = insertUser(ORGANIZATION_A);
    UUID space = insertSpace(ORGANIZATION_A, owner);
    UUID library = insertLibrary(ORGANIZATION_A, owner);

    insertAssociation(space, library, ORGANIZATION_A, owner);

    assertThat(countAssociations(space, library)).isEqualTo(1);
  }

  @Test
  void associatingTheSameLibraryTwiceIntoTheSameSpaceIsRejectedByTheUniqueConstraint()
      throws SQLException {
    UUID owner = insertUser(ORGANIZATION_A);
    UUID space = insertSpace(ORGANIZATION_A, owner);
    UUID library = insertLibrary(ORGANIZATION_A, owner);
    insertAssociation(space, library, ORGANIZATION_A, owner);

    assertThatThrownBy(() -> insertAssociation(space, library, ORGANIZATION_A, owner))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_space_asset_associations_space_library");
  }

  @Test
  void aCrossOrganizationAssociationIsRejected() throws SQLException {
    UUID owner = insertUser(ORGANIZATION_A);
    UUID space = insertSpace(ORGANIZATION_A, owner);
    UUID ownerInOtherOrganization = insertUser(ORGANIZATION_B);
    UUID libraryInOtherOrganization = insertLibrary(ORGANIZATION_B, ownerInOtherOrganization);

    assertThatThrownBy(
            () -> insertAssociation(space, libraryInOtherOrganization, ORGANIZATION_A, owner))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_space_asset_associations_library_organization");
  }

  @Test
  void deletingTheSpaceRemovesTheAssociationButLeavesTheLibraryUntouched() throws SQLException {
    UUID owner = insertUser(ORGANIZATION_A);
    UUID space = insertSpace(ORGANIZATION_A, owner);
    UUID library = insertLibrary(ORGANIZATION_A, owner);
    insertAssociation(space, library, ORGANIZATION_A, owner);

    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM spaces WHERE id = '" + space + "'");
    }

    assertThat(countAssociations(space, library)).isZero();
    assertThat(libraryStillExists(library)).isTrue();
  }

  @Test
  void deletingTheLibraryRemovesTheAssociation() throws SQLException {
    UUID owner = insertUser(ORGANIZATION_A);
    UUID space = insertSpace(ORGANIZATION_A, owner);
    UUID library = insertLibrary(ORGANIZATION_A, owner);
    insertAssociation(space, library, ORGANIZATION_A, owner);

    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM knowledge_libraries WHERE id = '" + library + "'");
    }

    assertThat(countAssociations(space, library)).isZero();
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

  private UUID insertUser(String organizationId) throws SQLException {
    UUID id = UUID.randomUUID();
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

  private UUID insertSpace(String organizationId, UUID ownerId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO spaces "
              + "(id, name, is_default, visibility, owner_id, organization_id, created_at,"
              + " updated_at) "
              + "VALUES ('"
              + id
              + "', 'Fachbereich', false, 'PRIVATE', '"
              + ownerId
              + "', '"
              + organizationId
              + "', now(), now())");
    }
    return id;
  }

  private UUID insertLibrary(String organizationId, UUID ownerId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type,"
              + " owner_user_id, visibility, listed, source_type, created_at, updated_at) "
              + "VALUES ('"
              + id
              + "', '"
              + organizationId
              + "', 'Bibliothek', 'USER', '"
              + ownerId
              + "', 'PRIVATE', false, 'UPLOAD', now(), now())");
    }
    return id;
  }

  private void insertAssociation(
      UUID spaceId, UUID libraryId, String organizationId, UUID createdBy) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO space_asset_associations"
              + " (id, space_id, library_id, organization_id, created_by_user_id, created_at) "
              + "VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + spaceId
              + "', '"
              + libraryId
              + "', '"
              + organizationId
              + "', '"
              + createdBy
              + "', now())");
    }
  }

  private int countAssociations(UUID spaceId, UUID libraryId) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM space_asset_associations WHERE space_id = '"
                    + spaceId
                    + "' AND library_id = '"
                    + libraryId
                    + "'")) {
      result.next();
      return result.getInt(1);
    }
  }

  private boolean libraryStillExists(UUID libraryId) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM knowledge_libraries WHERE id = '" + libraryId + "'")) {
      result.next();
      return result.getInt(1) == 1;
    }
  }
}
