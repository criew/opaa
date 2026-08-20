package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies Liquibase changelog 047 in isolation against a database built from the real, versioned
 * changelog through changeSet 046 - the same pattern as {@code
 * Migration046GroupsParentGroupOrganizationBindingTest}, with {@code test-master-through-046.yaml}
 * as the pre-migration fixture.
 *
 * <p><b>#289: reproduces the bug at the schema level before proving the fix.</b> {@link
 * #beforeTheMigrationASpaceCanBeOwnedByAUserFromAnotherOrganization} runs against the pre-migration
 * ({@code through-046}) fixture alone, without applying 047 - the exact defect the issue describes:
 * every single-column foreign key onto {@code users(id)} in a table that also carries {@code
 * organization_id} (17 of them, plus the non-user-referencing {@code fk_chats_space}) had no
 * organization dimension at all, so nothing on the database side stopped a row from naming a user
 * (or, for {@code fk_chats_space}, a space) belonging to a different organization. That test
 * succeeds where it should fail, which is the bug.
 *
 * <p>Given the scope (18 foreign keys, one migration), this class does not repeat one insert/assert
 * pair per foreign key. Instead it proves each of the three structural patterns once - a plain
 * {@code RESTRICT} composite key ({@code fk_spaces_owner_organization}), the {@code ON DELETE SET
 * NULL} column-list variant ({@code fk_group_membership_history_actor_user_organization}), and the
 * non-user-referencing {@code fk_chats_space_organization} - plus the membership-row cleanup
 * treatment ({@code space_memberships}), and then closes the gap with one structural test that
 * queries {@code pg_constraint} directly for all 18 constraint names and asserts every one of them
 * is truly composite (two columns), so no foreign key from the inventory was silently missed or
 * left single-column.
 */
class Migration047UserReferencesOrganizationBindingTest extends AbstractMigrationTest {

  private static final String ORGANIZATION_A = "00000000-0000-0000-0000-000000000001";
  private static final String ORGANIZATION_B = "00000000-0000-0000-0000-000000000002";

  /** The 18 composite foreign keys changelog 047 creates - see this file's class Javadoc. */
  private static final Set<String> EXPECTED_COMPOSITE_CONSTRAINTS =
      Set.of(
          "fk_spaces_owner_organization",
          "fk_space_memberships_user_organization",
          "fk_group_memberships_user_organization",
          "fk_knowledge_libraries_owner_user_organization",
          "fk_asset_grants_subject_user_organization",
          "fk_asset_grants_granted_by_user_organization",
          "fk_audit_actor_pseudonyms_user_organization",
          "fk_audit_incident_scope_grants_subject_organization",
          "fk_audit_incident_scope_grants_requester_organization",
          "fk_audit_incident_scope_grants_approver_organization",
          "fk_asset_grant_history_subject_user_organization",
          "fk_group_membership_history_user_organization",
          "fk_chats_author_organization",
          "fk_chats_space_organization",
          "fk_documents_uploaded_by_user_organization",
          "fk_asset_grant_history_actor_user_organization",
          "fk_group_membership_history_actor_user_organization",
          "fk_library_visibility_history_actor_user_organization");

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
  void beforeTheMigrationASpaceCanBeOwnedByAUserFromAnotherOrganization() throws Exception {
    // Deliberately does *not* call applyChangelog047() - this test proves the bug #289 describes
    // exists in the schema exactly as migration 008 left it, before this issue's fix is applied.
    UUID ownerInOrganizationB = insertUser(ORGANIZATION_B);

    // fk_spaces_owner only references users(id) - it has no organization dimension, so this
    // insert succeeds today even though the space and its owner belong to different
    // organizations. That is the defect #289 exists to close.
    UUID spaceInOrganizationA = insertSpace(ORGANIZATION_A, ownerInOrganizationB);

    assertThat(columnValue("spaces", "owner_id", spaceInOrganizationA))
        .isEqualTo(ownerInOrganizationB.toString());
  }

  @Test
  void afterTheMigrationASpaceCannotBeOwnedByAUserFromAnotherOrganization() throws Exception {
    applyChangelog047();
    UUID ownerInOrganizationB = insertUser(ORGANIZATION_B);

    // The composite foreign key fk_spaces_owner_organization references
    // users(id, organization_id) - a space naming this owner while itself belonging to
    // organization A must violate it, because no such (id, organization_id) pair exists in users.
    assertThatThrownBy(() -> insertSpace(ORGANIZATION_A, ownerInOrganizationB))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_spaces_owner_organization");
  }

  @Test
  void afterTheMigrationASpaceCanStillBeOwnedByAUserFromTheSameOrganization() throws Exception {
    applyChangelog047();
    UUID owner = insertUser(ORGANIZATION_A);

    UUID space = insertSpace(ORGANIZATION_A, owner);

    assertThat(columnValue("spaces", "owner_id", space)).isEqualTo(owner.toString());
  }

  @Test
  void clearsAPreExistingCrossOrganizationSpaceMembershipInsteadOfFailingTheMigration()
      throws Exception {
    // Deliberately built against the pre-migration schema (fk_space_memberships_user only), the
    // exact bug #289 describes - so this row can exist at all before 047 runs.
    UUID owner = insertUser(ORGANIZATION_A);
    UUID space = insertSpace(ORGANIZATION_A, owner);
    UUID memberInOrganizationB = insertUser(ORGANIZATION_B);
    insertSpaceMembership(space, memberInOrganizationB, ORGANIZATION_A);

    applyChangelog047();

    assertThat(spaceMembershipExists(space, memberInOrganizationB)).isFalse();
  }

  @Test
  void leavesASameOrganizationSpaceMembershipUntouchedByTheCleanupStep() throws Exception {
    UUID owner = insertUser(ORGANIZATION_A);
    UUID space = insertSpace(ORGANIZATION_A, owner);
    UUID member = insertUser(ORGANIZATION_A);
    insertSpaceMembership(space, member, ORGANIZATION_A);

    applyChangelog047();

    assertThat(spaceMembershipExists(space, member)).isTrue();
  }

  @Test
  void afterTheMigrationAGroupMembershipHistoryRowCannotNameAnActorFromAnotherOrganization()
      throws Exception {
    applyChangelog047();
    UUID actorInOrganizationB = insertUser(ORGANIZATION_B);
    UUID user = insertUser(ORGANIZATION_A);

    // fk_group_membership_history_actor_user_organization references users(id, organization_id) -
    // an actor from organization B on an organization-A history row must violate it.
    assertThatThrownBy(
            () -> insertGroupMembershipHistory(ORGANIZATION_A, user, actorInOrganizationB))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_group_membership_history_actor_user_organization");
  }

  @Test
  void deletingAnActorNullsOnlyTheHistoryRowsActorUserIdNotItsOrganizationId() throws Exception {
    applyChangelog047();
    UUID actor = insertUser(ORGANIZATION_A);
    UUID user = insertUser(ORGANIZATION_A);
    UUID historyRow = insertGroupMembershipHistory(ORGANIZATION_A, user, actor);

    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM users WHERE id = '" + actor + "'");
    }

    // Postgres 15+ column-list ON DELETE SET NULL (actor_user_id) - the composite key's other
    // column, organization_id, is NOT NULL and belongs to the history row itself, so it must
    // survive the actor's deletion untouched.
    assertThat(columnValue("group_membership_history", "actor_user_id", historyRow)).isNull();
    assertThat(columnValue("group_membership_history", "organization_id", historyRow))
        .isEqualTo(ORGANIZATION_A);
  }

  @Test
  void afterTheMigrationAChatCannotReferenceASpaceFromAnotherOrganization() throws Exception {
    applyChangelog047();
    UUID owner = insertUser(ORGANIZATION_B);
    UUID spaceInOrganizationB = insertSpace(ORGANIZATION_B, owner);
    UUID author = insertUser(ORGANIZATION_A);

    // fk_chats_space_organization references spaces(id, organization_id) - not a user reference
    // at all, but possible without any prerequisite since uk_spaces_id_organization (migration
    // 008) already existed; see the migration's own comment.
    assertThatThrownBy(() -> insertChat(ORGANIZATION_A, spaceInOrganizationB, author))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_chats_space_organization");
  }

  @Test
  void rollbackRestoresTheSingleColumnForeignKeys() throws Exception {
    applyChangelog047();

    rollbackChangelog047();

    // The composite condition is gone - a cross-organization owner succeeds again, exactly the
    // pre-#289 defect.
    UUID ownerInOrganizationB = insertUser(ORGANIZATION_B);
    UUID spaceInOrganizationA = insertSpace(ORGANIZATION_A, ownerInOrganizationB);
    assertThat(columnValue("spaces", "owner_id", spaceInOrganizationA))
        .isEqualTo(ownerInOrganizationB.toString());

    // But the single-column fk_spaces_owner (migration 008) is restored, not merely absent
    // alongside the composite one: an owner_id naming a user that does not exist at all -
    // regardless of organization - must still be rejected by *some* foreign key.
    UUID nonExistentOwnerId = UUID.randomUUID();
    assertThatThrownBy(() -> insertSpace(ORGANIZATION_A, nonExistentOwnerId))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_spaces_owner");

    // uk_users_id_organization is also gone again.
    assertThat(constraintExists("uk_users_id_organization")).isFalse();
  }

  @Test
  void everyForeignKeyFromTheInventoryIsTrulyComposite() throws Exception {
    applyChangelog047();

    Set<String> foundConstraints = new HashSet<>();
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT conname, array_length(conkey, 1) AS column_count FROM pg_constraint "
                    + "WHERE conname = ANY(ARRAY["
                    + quotedConstraintList()
                    + "])")) {
      while (result.next()) {
        String name = result.getString("conname");
        int columnCount = result.getInt("column_count");
        assertThat(columnCount)
            .as("constraint %s must be composite (2 columns)", name)
            .isEqualTo(2);
        foundConstraints.add(name);
      }
    }

    // Every constraint from the #289 inventory (17 user-referencing plus fk_chats_space) exists
    // and is composite - none was silently missed or left single-column.
    assertThat(foundConstraints).isEqualTo(EXPECTED_COMPOSITE_CONSTRAINTS);
  }

  private String quotedConstraintList() {
    StringBuilder builder = new StringBuilder();
    for (String name : EXPECTED_COMPOSITE_CONSTRAINTS) {
      if (builder.length() > 0) {
        builder.append(',');
      }
      builder.append('\'').append(name).append('\'');
    }
    return builder.toString();
  }

  private void applyChangelog047() throws Exception {
    applyChangelog(
        connection, "db/changelog/changes/047-bind-user-references-to-organization.yaml");
  }

  private void rollbackChangelog047() throws Exception {
    Liquibase liquibase =
        new Liquibase(
            "db/changelog/changes/047-bind-user-references-to-organization.yaml",
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

  private UUID insertUser(String organizationId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO users (id, organization_id, subject, issuer, created_at) VALUES ('"
              + id
              + "', '"
              + organizationId
              + "', 'subject-"
              + id
              + "', 'issuer', now())");
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

  private void insertSpaceMembership(UUID spaceId, UUID userId, String organizationId)
      throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO space_memberships (id, user_id, space_id, role, organization_id,"
              + " created_at) VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + userId
              + "', '"
              + spaceId
              + "', 'MEMBER', '"
              + organizationId
              + "', now())");
    }
  }

  private boolean spaceMembershipExists(UUID spaceId, UUID userId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM space_memberships WHERE space_id = ? AND user_id = ?")) {
      statement.setObject(1, spaceId);
      statement.setObject(2, userId);
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private UUID insertGroupMembershipHistory(String organizationId, UUID userId, UUID actorUserId)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO group_membership_history (id, group_id, organization_id, user_id, cause,"
              + " actor_user_id, valid_from, created_at) VALUES ('"
              + id
              + "', '"
              + UUID.randomUUID()
              + "', '"
              + organizationId
              + "', '"
              + userId
              + "', 'ADDED', '"
              + actorUserId
              + "', now(), now())");
    }
    return id;
  }

  private void insertChat(String organizationId, UUID spaceId, UUID authorId) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO chats (id, space_id, author_id, organization_id, created_at, updated_at)"
              + " VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + spaceId
              + "', '"
              + authorId
              + "', '"
              + organizationId
              + "', now(), now())");
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
