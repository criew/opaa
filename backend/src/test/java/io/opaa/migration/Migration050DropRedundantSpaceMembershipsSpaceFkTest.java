package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
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
 * Applies Liquibase changelog 050 in isolation against a database built from {@code
 * test-master-through-046.yaml} - the same fixture {@code
 * Migration047UserReferencesOrganizationBindingTest}, {@code
 * Migration048ChatLibraryReferencesOrganizationBindingTest} and {@code
 * Migration049BindIndexingJobsToOrganizationTest} already use: 050 touches only {@code
 * space_memberships} and {@code spaces}, both already fully in place by changeSet {@code
 * 008-add-space-memberships-organization} - it needs nothing from 047, 048 or 049.
 *
 * <p><b>#390: the redundant constraint {@code OrganizationBoundarySchemaTest} found.</b> Migration
 * 008 creates the single-column {@code fk_space_memberships_space} in changeSet {@code
 * 008-rename-workspace-memberships-to-space-memberships}, then - in the later changeSet {@code
 * 008-add-space-memberships-organization} - adds the composite {@code
 * fk_space_memberships_space_organization} referencing {@code spaces(id, organization_id)}, but
 * never drops the original single-column key. Both have coexisted, unnoticed, ever since; {@code
 * OrganizationBoundarySchemaTest}'s structural, schema-wide check is what first found it (see its
 * own Javadoc). This class proves changelog 050's fix and rollback the same way every other
 * migration in this package proves its own: {@link #beforeTheMigrationBothForeignKeysCoexist()}
 * reproduces the pre-050 state, {@link #afterTheMigrationOnlyTheCompositeForeignKeyRemains()} and
 * {@link #afterTheMigrationACrossOrganizationSpaceMembershipIsStillRejected()} prove the fix does
 * not weaken the boundary, and {@link
 * #rollbackRestoresTheOriginalSingleColumnForeignKeyWithCascadeSemantics()} proves the rollback is
 * faithful to migration 008's original definition, including its {@code ON DELETE CASCADE}
 * semantics (#390 review, confirmed identical on both constraints before this migration).
 */
class Migration050DropRedundantSpaceMembershipsSpaceFkTest extends AbstractMigrationTest {

  private static final String ORGANIZATION_A = "00000000-0000-0000-0000-000000000001";
  private static final String ORGANIZATION_B = "00000000-0000-0000-0000-000000000002";

  /** {@code confdeltype} value for {@code ON DELETE CASCADE} (see {@code pg_constraint}). */
  private static final char DELETE_CASCADE = 'c';

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-046.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    insertOrganization(ORGANIZATION_A);
    insertOrganization(ORGANIZATION_B);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void beforeTheMigrationBothForeignKeysCoexist() throws SQLException {
    // Deliberately does *not* call applyChangelog050() - proves the redundant pre-050 state
    // OrganizationBoundarySchemaTest found exists exactly as migration 008 left it.
    assertThat(constraintExists("fk_space_memberships_space")).isTrue();
    assertThat(constraintExists("fk_space_memberships_space_organization")).isTrue();
  }

  @Test
  void afterTheMigrationOnlyTheCompositeForeignKeyRemains() throws Exception {
    applyChangelog050();

    assertThat(constraintExists("fk_space_memberships_space")).isFalse();
    assertThat(constraintExists("fk_space_memberships_space_organization")).isTrue();
  }

  @Test
  void afterTheMigrationACrossOrganizationSpaceMembershipIsStillRejected() throws Exception {
    applyChangelog050();
    UUID owner = insertUser();
    UUID spaceInOrganizationB = insertSpace(ORGANIZATION_B, owner);
    UUID memberInOrganizationA = insertUser();

    // fk_space_memberships_space_organization alone (migration 008) already rejects this - the
    // redundant single-column key this migration drops never added anything the composite one did
    // not already enforce.
    assertThatThrownBy(
            () ->
                insertSpaceMembership(spaceInOrganizationB, memberInOrganizationA, ORGANIZATION_A))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_space_memberships_space_organization");
  }

  @Test
  void afterTheMigrationASameOrganizationSpaceMembershipStillWorks() throws Exception {
    applyChangelog050();
    UUID owner = insertUser();
    UUID space = insertSpace(ORGANIZATION_A, owner);
    UUID member = insertUser();

    UUID membership = insertSpaceMembership(space, member, ORGANIZATION_A);

    assertThat(columnValue("space_memberships", "user_id", membership))
        .isEqualTo(member.toString());
  }

  @Test
  void rollbackRestoresTheOriginalSingleColumnForeignKeyWithCascadeSemantics() throws Exception {
    applyChangelog050();

    rollbackChangelog050();

    // The original single-column foreign key from migration 008 is back, with the same ON DELETE
    // CASCADE it always had (#390 review: verified identical to the composite one's own CASCADE
    // before this migration, so dropping/restoring it changes no delete behaviour).
    assertThat(constraintExists("fk_space_memberships_space")).isTrue();
    assertThat(deleteAction("fk_space_memberships_space")).isEqualTo(DELETE_CASCADE);
    // fk_space_memberships_space_organization was never touched by 050 or its rollback.
    assertThat(constraintExists("fk_space_memberships_space_organization")).isTrue();
    assertThat(deleteAction("fk_space_memberships_space_organization")).isEqualTo(DELETE_CASCADE);

    // Behavioural proof, not just the catalog entry: deleting a space still cascades into its
    // memberships, exactly as migration 008 defined it.
    UUID owner = insertUser();
    UUID space = insertSpace(ORGANIZATION_A, owner);
    UUID member = insertUser();
    UUID membership = insertSpaceMembership(space, member, ORGANIZATION_A);

    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM spaces WHERE id = '" + space + "'");
    }

    assertThat(spaceMembershipExists(membership)).isFalse();
  }

  private void applyChangelog050() throws Exception {
    applyChangelog(
        connection, "db/changelog/changes/050-drop-redundant-space-memberships-space-fk.yaml");
  }

  private void rollbackChangelog050() throws Exception {
    Liquibase liquibase =
        new Liquibase(
            "db/changelog/changes/050-drop-redundant-space-memberships-space-fk.yaml",
            new ClassLoaderResourceAccessor(),
            liquibaseDatabase(connection));
    liquibase.rollback(1, (String) null);
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

  private UUID insertUser() throws SQLException {
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
              + ORGANIZATION_A
              + "')");
    }
    return id;
  }

  private UUID insertSpace(String organizationId, UUID ownerId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO spaces (id, organization_id, name, owner_id, created_at, updated_at)"
              + " VALUES ('"
              + id
              + "', '"
              + organizationId
              + "', 'Space "
              + id
              + "', '"
              + ownerId
              + "', now(), now())");
    }
    return id;
  }

  private UUID insertSpaceMembership(UUID spaceId, UUID userId, String organizationId)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO space_memberships (id, user_id, space_id, role, organization_id,"
              + " created_at) VALUES ('"
              + id
              + "', '"
              + userId
              + "', '"
              + spaceId
              + "', 'MEMBER', '"
              + organizationId
              + "', now())");
    }
    return id;
  }

  private boolean spaceMembershipExists(UUID id) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT 1 FROM space_memberships WHERE id = ?")) {
      statement.setObject(1, id);
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private boolean constraintExists(String constraintName) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT 1 FROM pg_constraint WHERE conname = ?")) {
      statement.setString(1, constraintName);
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private char deleteAction(String constraintName) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT confdeltype FROM pg_constraint WHERE conname = ?")) {
      statement.setString(1, constraintName);
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).as("constraint %s must exist", constraintName).isTrue();
        return result.getString("confdeltype").charAt(0);
      }
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
