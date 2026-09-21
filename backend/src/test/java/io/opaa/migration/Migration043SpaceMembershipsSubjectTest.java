package io.opaa.migration;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/043-space-memberships-subject.yaml} (#1815, ADR-0036 Entscheidung
 * 6): a space membership names a subject - person or group - and the existing person memberships
 * survive that unchanged.
 */
class Migration043SpaceMembershipsSubjectTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/043-space-memberships-subject.yaml";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
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
  void beforeTheChangesetAMembershipCanOnlyNameAPerson() throws Exception {
    assertThat(columnExists("space_memberships", "subject_type")).isFalse();
    assertThat(columnExists("space_memberships", "group_id")).isFalse();
    assertThat(columnExists("space_memberships", "member_count_at_grant")).isFalse();
    assertThat(constraintExists("uk_space_memberships_user_space")).isTrue();
  }

  @Test
  void theChangesetAddsTheSubjectColumnsTheChecksAndTheRestrictingForeignKey() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(columnExists("space_memberships", "subject_type")).isTrue();
    assertThat(columnExists("space_memberships", "group_id")).isTrue();
    assertThat(columnExists("space_memberships", "member_count_at_grant")).isTrue();
    assertThat(constraintExists("chk_space_memberships_subject")).isTrue();
    assertThat(constraintExists("chk_space_memberships_subject_type")).isTrue();
    assertThat(constraintExists("chk_space_memberships_member_count")).isTrue();
    assertThat(constraintExists("uk_space_memberships_user_space"))
        .as("the old person-only unique key is replaced by two partial ones")
        .isFalse();
    assertThat(indexDefinition("uk_space_memberships_user_subject"))
        .contains("(space_id, user_id)")
        .contains("'USER'");
    assertThat(indexDefinition("uk_space_memberships_group_subject"))
        .contains("(space_id, group_id)")
        .contains("'GROUP'");
    assertThat(deleteRuleOf("fk_space_memberships_group_organization")).isEqualTo("RESTRICT");
  }

  /** Datenerhalt: the bestand is migrated, not rebuilt - same rows, same users, same roles. */
  @Test
  void everyExistingMembershipKeepsItsRowAndBecomesAPersonSubject() throws Exception {
    Fixture fixture = seedSpaceWithTwoMembers();
    long before = count("SELECT count(*) FROM space_memberships");

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(count("SELECT count(*) FROM space_memberships")).isEqualTo(before);
    assertThat(
            count(
                "SELECT count(*) FROM space_memberships WHERE space_id = '"
                    + fixture.space()
                    + "' AND subject_type = 'USER' AND group_id IS NULL"))
        .isEqualTo(2);
    assertThat(
            stringOf(
                "SELECT role FROM space_memberships WHERE space_id = '"
                    + fixture.space()
                    + "' AND user_id = '"
                    + fixture.owner()
                    + "'"))
        .isEqualTo("ADMIN");
  }

  @Test
  void aGroupMembershipIsInsertableAndTheChecksRejectEveryMixedSubject() throws Exception {
    Fixture fixture = seedSpaceWithTwoMembers();

    applyChangelog(connection, CHANGELOG_PATH);

    execute(
        "INSERT INTO space_memberships (id, subject_type, group_id, space_id, role,"
            + " organization_id, member_count_at_grant) VALUES (?, 'GROUP', ?, ?, 'CURATOR', ?, 23)",
        UUID.randomUUID(),
        fixture.group(),
        fixture.space(),
        fixture.organization());

    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO space_memberships (id, subject_type, user_id, group_id, space_id,"
                        + " role, organization_id) VALUES (?, 'USER', ?, ?, ?, 'MEMBER', ?)",
                    UUID.randomUUID(),
                    fixture.owner(),
                    fixture.group(),
                    fixture.space(),
                    fixture.organization()))
        .hasMessageContaining("chk_space_memberships_subject");
    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO space_memberships (id, subject_type, space_id, role,"
                        + " organization_id) VALUES (?, 'GROUP', ?, 'MEMBER', ?)",
                    UUID.randomUUID(),
                    fixture.space(),
                    fixture.organization()))
        .hasMessageContaining("chk_space_memberships_subject");
  }

  /** The member count at grant belongs to a group row alone, and can never be negative. */
  @Test
  void theMemberCountAtGrantIsRejectedOnAPersonRowAndWhenNegative() throws Exception {
    Fixture fixture = seedSpaceWithTwoMembers();

    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO space_memberships (id, subject_type, user_id, space_id, role,"
                        + " organization_id, member_count_at_grant)"
                        + " VALUES (?, 'USER', ?, ?, 'MEMBER', ?, 5)",
                    UUID.randomUUID(),
                    seedUser(fixture.organization()),
                    fixture.space(),
                    fixture.organization()))
        .hasMessageContaining("chk_space_memberships_member_count");
    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO space_memberships (id, subject_type, group_id, space_id, role,"
                        + " organization_id, member_count_at_grant)"
                        + " VALUES (?, 'GROUP', ?, ?, 'MEMBER', ?, -1)",
                    UUID.randomUUID(),
                    fixture.group(),
                    fixture.space(),
                    fixture.organization()))
        .hasMessageContaining("chk_space_memberships_member_count");
  }

  /** The two partial unique indexes: one row per subject and space, group and person apart. */
  @Test
  void aSubjectCannotHoldTwoMembershipsOfTheSameSpace() throws Exception {
    Fixture fixture = seedSpaceWithTwoMembers();

    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO space_memberships (id, subject_type, user_id, space_id, role,"
                        + " organization_id) VALUES (?, 'USER', ?, ?, 'MEMBER', ?)",
                    UUID.randomUUID(),
                    fixture.owner(),
                    fixture.space(),
                    fixture.organization()))
        .hasMessageContaining("uk_space_memberships_user_subject");
    execute(
        "INSERT INTO space_memberships (id, subject_type, group_id, space_id, role,"
            + " organization_id) VALUES (?, 'GROUP', ?, ?, 'MEMBER', ?)",
        UUID.randomUUID(),
        fixture.group(),
        fixture.space(),
        fixture.organization());
    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO space_memberships (id, subject_type, group_id, space_id, role,"
                        + " organization_id) VALUES (?, 'GROUP', ?, ?, 'ADMIN', ?)",
                    UUID.randomUUID(),
                    fixture.group(),
                    fixture.space(),
                    fixture.organization()))
        .hasMessageContaining("uk_space_memberships_group_subject");
  }

  /** {@code ON DELETE RESTRICT} is what carries "no group vanishes out of a space it holds". */
  @Test
  void aGroupThatIsASpaceMemberCannotBeDeletedByTheDatabase() throws Exception {
    Fixture fixture = seedSpaceWithTwoMembers();

    applyChangelog(connection, CHANGELOG_PATH);

    execute(
        "INSERT INTO space_memberships (id, subject_type, group_id, space_id, role,"
            + " organization_id) VALUES (?, 'GROUP', ?, ?, 'MEMBER', ?)",
        UUID.randomUUID(),
        fixture.group(),
        fixture.space(),
        fixture.organization());

    assertThatThrownBy(() -> execute("DELETE FROM groups WHERE id = ?", fixture.group()))
        .hasMessageContaining("fk_space_memberships_group_organization");
  }

  /** The composite key holds the organization boundary of ADR-0036, Randbedingung 13. */
  @Test
  void aGroupOfAnotherOrganizationCannotBecomeAMember() throws Exception {
    Fixture fixture = seedSpaceWithTwoMembers();
    UUID foreignOrganization = seedOrganization();
    UUID foreignGroup = seedGroup(foreignOrganization);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO space_memberships (id, subject_type, group_id, space_id, role,"
                        + " organization_id) VALUES (?, 'GROUP', ?, ?, 'MEMBER', ?)",
                    UUID.randomUUID(),
                    foreignGroup,
                    fixture.space(),
                    fixture.organization()))
        .hasMessageContaining("fk_space_memberships_group_organization");
  }

  @Test
  void theChangelogIsReferencedByTheMasterChangelog() throws Exception {
    assertThat(masterChangelog()).contains(CHANGELOG_PATH);
  }

  // -----------------------------------------------------------------------------------------
  // Fixture
  // -----------------------------------------------------------------------------------------

  private record Fixture(UUID organization, UUID owner, UUID member, UUID space, UUID group) {}

  private Fixture seedSpaceWithTwoMembers() throws SQLException {
    UUID organization = seedOrganization();
    UUID owner = seedUser(organization);
    UUID member = seedUser(organization);
    UUID space = seedSpace(organization, owner);
    seedMembership(organization, space, owner, "ADMIN");
    seedMembership(organization, space, member, "MEMBER");
    return new Fixture(organization, owner, member, space, seedGroup(organization));
  }

  private UUID seedOrganization() throws SQLException {
    UUID organization = UUID.randomUUID();
    execute(
        "INSERT INTO organizations (id, name) VALUES (?, ?)",
        organization,
        "Organisation " + organization);
    return organization;
  }

  private UUID seedUser(UUID organization) throws SQLException {
    UUID user = UUID.randomUUID();
    execute(
        "INSERT INTO users (id, subject, issuer, organization_id) VALUES (?, ?, ?, ?)",
        user,
        "subject-" + user,
        "https://a.example",
        organization);
    return user;
  }

  private UUID seedSpace(UUID organization, UUID owner) throws SQLException {
    UUID space = UUID.randomUUID();
    execute(
        "INSERT INTO spaces (id, name, owner_id, organization_id, visibility)"
            + " VALUES (?, 'Team', ?, ?, 'PRIVATE')",
        space,
        owner,
        organization);
    return space;
  }

  private void seedMembership(UUID organization, UUID space, UUID user, String role)
      throws SQLException {
    execute(
        "INSERT INTO space_memberships (id, user_id, space_id, role, organization_id)"
            + " VALUES (?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        user,
        space,
        role,
        organization);
  }

  private UUID seedGroup(UUID organization) throws SQLException {
    UUID group = UUID.randomUUID();
    execute(
        "INSERT INTO groups (id, organization_id, kind, name) VALUES (?, ?, 'AD_HOC', ?)",
        group,
        organization,
        "Referat " + group);
    return group;
  }

  private String masterChangelog() throws Exception {
    return new String(
        requireNonNull(
                getClass()
                    .getClassLoader()
                    .getResourceAsStream("db/changelog/db.changelog-master.yaml"))
            .readAllBytes(),
        StandardCharsets.UTF_8);
  }

  private void execute(String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.executeUpdate();
    }
  }

  private long count(String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      assertThat(rows.next()).isTrue();
      return rows.getLong(1);
    }
  }

  private String stringOf(String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      assertThat(rows.next()).isTrue();
      return rows.getString(1);
    }
  }

  private boolean columnExists(String table, String column) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM information_schema.columns WHERE table_schema = current_schema()"
                + " AND table_name = ? AND column_name = ?")) {
      statement.setString(1, table);
      statement.setString(2, column);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }

  private boolean constraintExists(String name) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT 1 FROM pg_constraint WHERE conname = ?")) {
      statement.setString(1, name);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }

  private String deleteRuleOf(String constraintName) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT confdeltype FROM pg_constraint WHERE conname = ? AND contype = 'f'")) {
      statement.setString(1, constraintName);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return switch (rows.getString(1)) {
          case "r" -> "RESTRICT";
          case "c" -> "CASCADE";
          case "n" -> "SET NULL";
          case "a" -> "NO ACTION";
          default -> rows.getString(1);
        };
      }
    }
  }

  private String indexDefinition(String name) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema()"
                + " AND indexname = ?")) {
      statement.setString(1, name);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getString(1);
      }
    }
  }
}
