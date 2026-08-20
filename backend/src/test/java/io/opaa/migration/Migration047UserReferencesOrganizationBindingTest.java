package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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
 * <p><b>Two layers of proof, per PR #678 review:</b>
 *
 * <ul>
 *   <li>Behavioural tests (insert/assert pairs) prove each of the structural patterns actually
 *       rejects a cross-organization row and still accepts a same-organization one - a plain {@code
 *       RESTRICT} composite key ({@code fk_spaces_owner_organization}), the membership-row cleanup
 *       treatment ({@code space_memberships}), the {@code ON DELETE SET NULL} column-list variant
 *       ({@code fk_group_membership_history_actor_user_organization}), the non-user-referencing
 *       {@code fk_chats_space_organization}, and the one case the migration deliberately leaves
 *       alone - a {@code GROUP} asset grant with {@code subject_user_id IS NULL}, still insertable
 *       because MATCH SIMPLE never evaluates a composite key with a NULL member.
 *   <li>{@link #everyForeignKeyFromTheInventoryMatchesItsExpectedShape()} closes the gap those
 *       individual tests cannot: it checks all 18 constraints from the {@link
 *       #EXPECTED_FOREIGN_KEYS} table against {@code pg_constraint} directly - not just "is this
 *       composite" but the exact base/referenced column pairs (via {@code conkey}/{@code confkey}
 *       resolved through {@code pg_attribute}), the referenced table, {@code confdeltype}, and -
 *       for the four {@code SET NULL} cases - {@code confdelsetcols}, proving only the user column
 *       is nulled, never {@code organization_id}. {@link
 *       #noSingleColumnForeignKeyToUsersRemainsOnATableThatCarriesOrganizationId()} is the
 *       complementary negative check: it does not consult {@link #EXPECTED_FOREIGN_KEYS} at all, so
 *       a foreign key this migration missed (today) or a new one added later without the composite
 *       pattern (tomorrow) would still be caught - the same idea #390's structural schema-wide
 *       check applies project-wide, run here in miniature for one migration.
 * </ul>
 */
class Migration047UserReferencesOrganizationBindingTest extends AbstractMigrationTest {

  private static final String ORGANIZATION_A = "00000000-0000-0000-0000-000000000001";
  private static final String ORGANIZATION_B = "00000000-0000-0000-0000-000000000002";

  /** {@code confdeltype} values (see Postgres' {@code pg_constraint} documentation). */
  private static final char DELETE_RESTRICT = 'r';

  private static final char DELETE_CASCADE = 'c';
  private static final char DELETE_SET_NULL = 'n';

  /**
   * The exact shape - base/referenced columns in declaration order, referenced table, {@code ON
   * DELETE} action, and (for {@code SET NULL}) the column-list target - of every one of the 18
   * composite foreign keys changelog 047 creates. See this file's class Javadoc, {@link
   * #everyForeignKeyFromTheInventoryMatchesItsExpectedShape()}.
   */
  private static final List<ExpectedForeignKey> EXPECTED_FOREIGN_KEYS =
      List.of(
          ExpectedForeignKey.restrict(
              "fk_spaces_owner_organization",
              "spaces",
              List.of("owner_id", "organization_id"),
              "users",
              List.of("id", "organization_id")),
          ExpectedForeignKey.cascade(
              "fk_space_memberships_user_organization",
              "space_memberships",
              List.of("user_id", "organization_id"),
              "users",
              List.of("id", "organization_id")),
          ExpectedForeignKey.cascade(
              "fk_group_memberships_user_organization",
              "group_memberships",
              List.of("user_id", "organization_id"),
              "users",
              List.of("id", "organization_id")),
          ExpectedForeignKey.restrict(
              "fk_knowledge_libraries_owner_user_organization",
              "knowledge_libraries",
              List.of("owner_user_id", "organization_id"),
              "users",
              List.of("id", "organization_id")),
          ExpectedForeignKey.restrict(
              "fk_asset_grants_subject_user_organization",
              "asset_grants",
              List.of("subject_user_id", "organization_id"),
              "users",
              List.of("id", "organization_id")),
          ExpectedForeignKey.restrict(
              "fk_asset_grants_granted_by_user_organization",
              "asset_grants",
              List.of("granted_by_user_id", "organization_id"),
              "users",
              List.of("id", "organization_id")),
          ExpectedForeignKey.cascade(
              "fk_audit_actor_pseudonyms_user_organization",
              "audit_actor_pseudonyms",
              List.of("user_id", "organization_id"),
              "users",
              List.of("id", "organization_id")),
          ExpectedForeignKey.restrict(
              "fk_audit_incident_scope_grants_subject_organization",
              "audit_incident_scope_grants",
              List.of("subject_user_id", "organization_id"),
              "users",
              List.of("id", "organization_id")),
          ExpectedForeignKey.restrict(
              "fk_audit_incident_scope_grants_requester_organization",
              "audit_incident_scope_grants",
              List.of("requested_by_user_id", "organization_id"),
              "users",
              List.of("id", "organization_id")),
          ExpectedForeignKey.restrict(
              "fk_audit_incident_scope_grants_approver_organization",
              "audit_incident_scope_grants",
              List.of("approved_by_user_id", "organization_id"),
              "users",
              List.of("id", "organization_id")),
          ExpectedForeignKey.restrict(
              "fk_asset_grant_history_subject_user_organization",
              "asset_grant_history",
              List.of("subject_user_id", "organization_id"),
              "users",
              List.of("id", "organization_id")),
          ExpectedForeignKey.restrict(
              "fk_group_membership_history_user_organization",
              "group_membership_history",
              List.of("user_id", "organization_id"),
              "users",
              List.of("id", "organization_id")),
          ExpectedForeignKey.restrict(
              "fk_chats_author_organization",
              "chats",
              List.of("author_id", "organization_id"),
              "users",
              List.of("id", "organization_id")),
          ExpectedForeignKey.restrict(
              "fk_chats_space_organization",
              "chats",
              List.of("space_id", "organization_id"),
              "spaces",
              List.of("id", "organization_id")),
          ExpectedForeignKey.setNull(
              "fk_documents_uploaded_by_user_organization",
              "documents",
              List.of("uploaded_by_user_id", "organization_id"),
              "users",
              List.of("id", "organization_id"),
              List.of("uploaded_by_user_id")),
          ExpectedForeignKey.setNull(
              "fk_asset_grant_history_actor_user_organization",
              "asset_grant_history",
              List.of("actor_user_id", "organization_id"),
              "users",
              List.of("id", "organization_id"),
              List.of("actor_user_id")),
          ExpectedForeignKey.setNull(
              "fk_group_membership_history_actor_user_organization",
              "group_membership_history",
              List.of("actor_user_id", "organization_id"),
              "users",
              List.of("id", "organization_id"),
              List.of("actor_user_id")),
          ExpectedForeignKey.setNull(
              "fk_library_visibility_history_actor_user_organization",
              "library_visibility_history",
              List.of("actor_user_id", "organization_id"),
              "users",
              List.of("id", "organization_id"),
              List.of("actor_user_id")));

  /**
   * The single-column shape changelog 047's rollback must restore for the four {@code SET NULL}
   * cases - {@code confdelsetcols} is empty here because a plain (non-composite) {@code ON DELETE
   * SET NULL} foreign key never uses Postgres' column-list syntax; that syntax only exists for
   * composite keys. See {@link
   * #rollbackRestoresAllEighteenSingleColumnForeignKeysWithTheirOriginalBehaviour()}.
   */
  private static final List<ExpectedForeignKey> ORIGINAL_SET_NULL_FOREIGN_KEYS =
      List.of(
          ExpectedForeignKey.originalSetNull(
              "fk_documents_uploaded_by_user", "documents", "uploaded_by_user_id", "users"),
          ExpectedForeignKey.originalSetNull(
              "fk_asset_grant_history_actor_user", "asset_grant_history", "actor_user_id", "users"),
          ExpectedForeignKey.originalSetNull(
              "fk_group_membership_history_actor_user",
              "group_membership_history",
              "actor_user_id",
              "users"),
          ExpectedForeignKey.originalSetNull(
              "fk_library_visibility_history_actor_user",
              "library_visibility_history",
              "actor_user_id",
              "users"));

  private static final ExpectedForeignKey ORIGINAL_SPACES_OWNER_FOREIGN_KEY =
      new ExpectedForeignKey(
          "fk_spaces_owner",
          "spaces",
          List.of("owner_id"),
          "users",
          List.of("id"),
          DELETE_RESTRICT,
          List.of());

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
  void aGroupAssetGrantWithNoSubjectUserIsStillInsertableAfterTheMigration() throws Exception {
    applyChangelog047();

    // fk_asset_grants_subject_user_organization is a composite key on
    // (subject_user_id, organization_id) - under Postgres' default MATCH SIMPLE, a composite
    // foreign key is not evaluated at all once any one of its columns is NULL. A GROUP grant
    // always carries subject_user_id = NULL (chk_asset_grants_subject, migration 013), so this
    // insert must keep succeeding exactly as it did before 047 - the organization check for a
    // group subject runs through fk_asset_grants_subject_group_organization (migration 013)
    // instead, unaffected by this migration.
    UUID group = insertGroup(ORGANIZATION_A);
    UUID ownerGroup = insertGroup(ORGANIZATION_A);
    UUID library = insertGroupOwnedLibrary(ORGANIZATION_A, ownerGroup);

    UUID grant = insertGroupAssetGrant(ORGANIZATION_A, library, group);

    assertThat(columnValue("asset_grants", "subject_user_id", grant)).isNull();
    assertThat(columnValue("asset_grants", "subject_group_id", grant)).isEqualTo(group.toString());
  }

  @Test
  void everyForeignKeyFromTheInventoryMatchesItsExpectedShape() throws Exception {
    applyChangelog047();

    for (ExpectedForeignKey expected : EXPECTED_FOREIGN_KEYS) {
      assertForeignKeyMatches(expected);
    }
  }

  /**
   * The complement to {@link #everyForeignKeyFromTheInventoryMatchesItsExpectedShape()}: instead of
   * checking the 18 constraints this migration is *supposed* to have created, this queries {@code
   * pg_constraint} for any single-column foreign key onto {@code users(id)} whose base table
   * carries an {@code organization_id} column at all - the exact shape of the bug #289 closes,
   * found without consulting {@link #EXPECTED_FOREIGN_KEYS}. A foreign key this migration missed
   * today, or a new one added later without the composite pattern, would show up here.
   */
  @Test
  void noSingleColumnForeignKeyToUsersRemainsOnATableThatCarriesOrganizationId() throws Exception {
    applyChangelog047();

    assertThat(singleColumnUserForeignKeysOnOrganizationScopedTables()).isEmpty();
  }

  /**
   * Sanity check for the query {@link
   * #noSingleColumnForeignKeyToUsersRemainsOnATableThatCarriesOrganizationId()} relies on: run
   * before the migration, against the exact bug #289 describes, it must find every one of the 17
   * user-referencing single-column foreign keys from the inventory - proving the query is
   * meaningful, not vacuously empty by construction.
   */
  @Test
  void beforeTheMigrationTheNegativeCheckQueryFindsAllSeventeenUserForeignKeys() throws Exception {
    assertThat(singleColumnUserForeignKeysOnOrganizationScopedTables()).hasSize(17);
  }

  @Test
  void rollbackRestoresAllEighteenSingleColumnForeignKeysWithTheirOriginalBehaviour()
      throws Exception {
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

    // Structurally: fk_spaces_owner (one representative RESTRICT case) and all four SET NULL
    // foreign keys - the only rollback statements without Liquibase's own drop/re-add
    // bookkeeping, so the ones most exposed to a typo in the raw SQL - are back to their original,
    // single-column, correctly-behaved shape.
    assertForeignKeyMatches(ORIGINAL_SPACES_OWNER_FOREIGN_KEY);
    for (ExpectedForeignKey expected : ORIGINAL_SET_NULL_FOREIGN_KEYS) {
      assertForeignKeyMatches(expected);
    }
  }

  /**
   * Queries {@code pg_constraint} for every column pair, delete action and (for {@code SET NULL})
   * column-list target of {@code expected.constraintName()}, resolving {@code conkey}/{@code
   * confkey} to real column names via {@code pg_attribute} rather than trusting column counts alone
   * (PR #678 review, finding 1).
   */
  private void assertForeignKeyMatches(ExpectedForeignKey expected) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT contype, confdeltype, conrelid::regclass::text AS base_table,"
                + " confrelid::regclass::text AS referenced_table FROM pg_constraint"
                + " WHERE conname = ? AND contype = 'f'")) {
      statement.setString(1, expected.constraintName());
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next())
            .as("foreign key %s must exist", expected.constraintName())
            .isTrue();
        assertThat(result.getString("base_table"))
            .as("base table of %s", expected.constraintName())
            .isEqualTo(expected.baseTable());
        assertThat(result.getString("referenced_table"))
            .as("referenced table of %s", expected.constraintName())
            .isEqualTo(expected.referencedTable());
        assertThat(result.getString("confdeltype"))
            .as("ON DELETE action of %s", expected.constraintName())
            .isEqualTo(String.valueOf(expected.deleteAction()));
      }
    }
    assertThat(resolvedColumns(expected.constraintName(), "conkey", "conrelid"))
        .as("base columns of %s", expected.constraintName())
        .isEqualTo(expected.baseColumns());
    assertThat(resolvedColumns(expected.constraintName(), "confkey", "confrelid"))
        .as("referenced columns of %s", expected.constraintName())
        .isEqualTo(expected.referencedColumns());
    assertThat(resolvedColumns(expected.constraintName(), "confdelsetcols", "conrelid"))
        .as("ON DELETE SET NULL column-list target of %s", expected.constraintName())
        .isEqualTo(expected.setNullColumns());
  }

  /** Resolves an {@code int2vector}/{@code smallint[]} attribute-number column to column names. */
  private List<String> resolvedColumns(String constraintName, String keyColumn, String relIdColumn)
      throws SQLException {
    List<String> columns = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT a.attname FROM pg_constraint c"
                + " JOIN unnest(c."
                + keyColumn
                + ") WITH ORDINALITY AS k(attnum, ord) ON true"
                + " JOIN pg_attribute a ON a.attrelid = c."
                + relIdColumn
                + " AND a.attnum = k.attnum"
                + " WHERE c.conname = ? ORDER BY k.ord")) {
      statement.setString(1, constraintName);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          columns.add(result.getString("attname"));
        }
      }
    }
    return columns;
  }

  /**
   * The negative check itself (PR #678 review, finding 2): every single-column foreign key onto
   * {@code users(id)} whose base table carries a (non-dropped) {@code organization_id} column -
   * exactly the shape #289 describes, found without any hand-maintained list of constraint names.
   */
  private List<String> singleColumnUserForeignKeysOnOrganizationScopedTables() throws SQLException {
    List<String> found = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT c.conname FROM pg_constraint c"
                    + " JOIN pg_attribute org ON org.attrelid = c.conrelid"
                    + "   AND org.attname = 'organization_id' AND NOT org.attisdropped"
                    + " WHERE c.contype = 'f'"
                    + "   AND c.confrelid = 'users'::regclass"
                    + "   AND array_length(c.conkey, 1) = 1")) {
      while (result.next()) {
        found.add(result.getString("conname"));
      }
    }
    return found;
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

  private UUID insertGroup(String organizationId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO groups (id, organization_id, kind, name, created_at, updated_at) VALUES ('"
              + id
              + "', '"
              + organizationId
              + "', 'AD_HOC', 'Gruppe "
              + id
              + "', now(), now())");
    }
    return id;
  }

  private UUID insertGroupOwnedLibrary(String organizationId, UUID ownerGroupId)
      throws SQLException {
    // owner_type = 'SYSTEM' no longer exists (migration 031 narrowed chk_knowledge_libraries_owner
    // to USER/GROUP after #521 removed the system library) - GROUP ownership needs no user row,
    // which is exactly what this helper is for.
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type,"
              + " owner_group_id, visibility, source_type, created_at, updated_at) VALUES ('"
              + id
              + "', '"
              + organizationId
              + "', 'Bibliothek "
              + id
              + "', 'GROUP', '"
              + ownerGroupId
              + "', 'PRIVATE', 'UPLOAD', now(), now())");
    }
    return id;
  }

  private UUID insertGroupAssetGrant(String organizationId, UUID libraryId, UUID groupId)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO asset_grants (id, library_id, organization_id, subject_type,"
              + " subject_group_id, role, created_at, updated_at) VALUES ('"
              + id
              + "', '"
              + libraryId
              + "', '"
              + organizationId
              + "', 'GROUP', '"
              + groupId
              + "', 'VIEWER', now(), now())");
    }
    return id;
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

  /**
   * The expected shape of one foreign key, as read directly from {@code pg_constraint}: base and
   * referenced columns in declaration order, the referenced table, the {@code ON DELETE} action,
   * and - only for {@code SET NULL} - the column(s) Postgres' column-list syntax nulls. See PR #678
   * review, finding 1.
   */
  private record ExpectedForeignKey(
      String constraintName,
      String baseTable,
      List<String> baseColumns,
      String referencedTable,
      List<String> referencedColumns,
      char deleteAction,
      List<String> setNullColumns) {

    static ExpectedForeignKey restrict(
        String constraintName,
        String baseTable,
        List<String> baseColumns,
        String referencedTable,
        List<String> referencedColumns) {
      return new ExpectedForeignKey(
          constraintName,
          baseTable,
          baseColumns,
          referencedTable,
          referencedColumns,
          DELETE_RESTRICT,
          List.of());
    }

    static ExpectedForeignKey cascade(
        String constraintName,
        String baseTable,
        List<String> baseColumns,
        String referencedTable,
        List<String> referencedColumns) {
      return new ExpectedForeignKey(
          constraintName,
          baseTable,
          baseColumns,
          referencedTable,
          referencedColumns,
          DELETE_CASCADE,
          List.of());
    }

    static ExpectedForeignKey setNull(
        String constraintName,
        String baseTable,
        List<String> baseColumns,
        String referencedTable,
        List<String> referencedColumns,
        List<String> setNullColumns) {
      return new ExpectedForeignKey(
          constraintName,
          baseTable,
          baseColumns,
          referencedTable,
          referencedColumns,
          DELETE_SET_NULL,
          setNullColumns);
    }

    /** A pre-047/rolled-back single-column {@code ON DELETE SET NULL} foreign key onto users. */
    static ExpectedForeignKey originalSetNull(
        String constraintName, String baseTable, String baseColumn, String referencedTable) {
      return new ExpectedForeignKey(
          constraintName,
          baseTable,
          List.of(baseColumn),
          referencedTable,
          List.of("id"),
          DELETE_SET_NULL,
          List.of());
    }
  }
}
