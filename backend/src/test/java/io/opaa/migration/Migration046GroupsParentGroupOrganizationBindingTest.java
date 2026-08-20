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
 * Applies Liquibase changelog 046 in isolation against a database built from the real, versioned
 * changelog through changeSet 045 - the same pattern as {@code
 * Migration045KeyRssFeedStateByLibraryTest}, with {@code test-master-through-045.yaml} as the
 * pre-migration fixture.
 *
 * <p><b>#400: reproduces the bug at the schema level before proving the fix.</b> {@link
 * #beforeTheMigrationAGroupCanHaveAParentGroupFromAnotherOrganization} runs against the
 * pre-migration ({@code through-045}) fixture alone, without applying 046 - the exact defect the
 * issue describes: {@code fk_groups_parent_group} (migration 009) only referenced {@code
 * groups(id)}, not {@code groups(id, organization_id)}, so nothing on the database side stopped a
 * group's {@code parent_group_id} from naming a group in a different organization. That test
 * succeeds where it should fail, which is the bug. Every other test in this class applies 046 and
 * proves the fixed behavior: a cross-organization parent link is rejected by the new composite
 * foreign key, a same-organization parent link still works, a pre-existing cross-organization row
 * is cleared rather than left to break the migration, and deleting a parent group nulls out only
 * the child's {@code parent_group_id} - never its {@code organization_id}.
 */
class Migration046GroupsParentGroupOrganizationBindingTest extends AbstractMigrationTest {

  private static final String ORGANIZATION_A = "00000000-0000-0000-0000-000000000001";
  private static final String ORGANIZATION_B = "00000000-0000-0000-0000-000000000002";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-045.yaml";
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
  void beforeTheMigrationAGroupCanHaveAParentGroupFromAnotherOrganization() throws Exception {
    // Deliberately does *not* call applyChangelog046() - this test proves the bug #400 describes
    // exists in the schema exactly as migration 009 left it, before this issue's fix is applied.
    UUID parentInOrganizationB = insertGroup(ORGANIZATION_B, null);

    // fk_groups_parent_group only references groups(id) - it has no organization dimension, so
    // this insert succeeds today even though the child and its parent belong to different
    // organizations. That is the defect #400 exists to close.
    UUID childInOrganizationA = insertGroup(ORGANIZATION_A, parentInOrganizationB);

    assertThat(columnValue("groups", "parent_group_id", childInOrganizationA))
        .isEqualTo(parentInOrganizationB.toString());
  }

  @Test
  void afterTheMigrationAGroupCannotHaveAParentGroupFromAnotherOrganization() throws Exception {
    applyChangelog046();
    UUID parentInOrganizationB = insertGroup(ORGANIZATION_B, null);

    // The composite foreign key fk_groups_parent_group_organization references
    // groups(id, organization_id) - a child naming this parent while itself belonging to
    // organization A must violate it, because no such (id, organization_id) pair exists in groups.
    assertThatThrownBy(() -> insertGroup(ORGANIZATION_A, parentInOrganizationB))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_groups_parent_group_organization");
  }

  @Test
  void afterTheMigrationAGroupCanStillHaveAParentGroupFromTheSameOrganization() throws Exception {
    applyChangelog046();
    UUID parent = insertGroup(ORGANIZATION_A, null);

    UUID child = insertGroup(ORGANIZATION_A, parent);

    assertThat(columnValue("groups", "parent_group_id", child)).isEqualTo(parent.toString());
  }

  @Test
  void clearsAPreExistingCrossOrganizationParentLinkInsteadOfFailingTheMigration()
      throws Exception {
    // Deliberately built against the pre-migration schema (fk_groups_parent_group only), the
    // exact bug #400 describes - so this row can exist at all before 046 runs.
    UUID parentInOrganizationB = insertGroup(ORGANIZATION_B, null);
    UUID childInOrganizationA = insertGroup(ORGANIZATION_A, parentInOrganizationB);

    applyChangelog046();

    assertThat(columnValue("groups", "parent_group_id", childInOrganizationA)).isNull();
  }

  @Test
  void leavesASameOrganizationParentLinkUntouchedByTheCleanupStep() throws Exception {
    UUID parent = insertGroup(ORGANIZATION_A, null);
    UUID child = insertGroup(ORGANIZATION_A, parent);

    applyChangelog046();

    assertThat(columnValue("groups", "parent_group_id", child)).isEqualTo(parent.toString());
  }

  @Test
  void deletingAParentGroupNullsOnlyTheChildsParentGroupIdNotItsOrganizationId() throws Exception {
    applyChangelog046();
    UUID parent = insertGroup(ORGANIZATION_A, null);
    UUID child = insertGroup(ORGANIZATION_A, parent);

    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM groups WHERE id = '" + parent + "'");
    }

    // Postgres 15+ column-list ON DELETE SET NULL (parent_group_id) - the composite key's other
    // column, organization_id, is NOT NULL and belongs to the child row itself, so it must survive
    // its parent's deletion untouched.
    assertThat(columnValue("groups", "parent_group_id", child)).isNull();
    assertThat(columnValue("groups", "organization_id", child)).isEqualTo(ORGANIZATION_A);
  }

  @Test
  void rollbackRestoresTheSingleColumnForeignKey() throws Exception {
    applyChangelog046();

    rollbackChangelog046();

    // The composite condition is gone - a cross-organization parent link succeeds again, exactly
    // the pre-#400 defect.
    UUID parentInOrganizationB = insertGroup(ORGANIZATION_B, null);
    UUID childInOrganizationA = insertGroup(ORGANIZATION_A, parentInOrganizationB);
    assertThat(columnValue("groups", "parent_group_id", childInOrganizationA))
        .isEqualTo(parentInOrganizationB.toString());

    // But the single-column fk_groups_parent_group (migration 009) is restored, not merely
    // absent alongside the composite one: a parent_group_id naming a group that does not exist at
    // all - regardless of organization - must still be rejected by *some* foreign key.
    UUID nonExistentParentId = UUID.randomUUID();
    assertThatThrownBy(() -> insertGroup(ORGANIZATION_A, nonExistentParentId))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_groups_parent_group");
  }

  private void applyChangelog046() throws Exception {
    applyChangelog(
        connection, "db/changelog/changes/046-bind-groups-parent-group-to-organization.yaml");
  }

  private void rollbackChangelog046() throws Exception {
    Liquibase liquibase =
        new Liquibase(
            "db/changelog/changes/046-bind-groups-parent-group-to-organization.yaml",
            new ClassLoaderResourceAccessor(),
            liquibaseDatabase(connection));
    liquibase.rollback(2, (String) null);
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

  private UUID insertGroup(String organizationId, UUID parentGroupId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO groups (id, organization_id, kind, name, parent_group_id, created_at,"
              + " updated_at) VALUES ('"
              + id
              + "', '"
              + organizationId
              + "', 'AD_HOC', 'Gruppe "
              + id
              + "', "
              + (parentGroupId == null ? "NULL" : "'" + parentGroupId + "'")
              + ", now(), now())");
    }
    return id;
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
