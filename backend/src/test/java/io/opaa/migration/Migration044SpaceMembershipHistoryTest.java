package io.opaa.migration;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/044-create-space-membership-history.yaml} (#1815, ADR-0036
 * Entscheidung 8): the rights history of the space membership, its ADR-0016 column rules, and the
 * open interval every existing membership receives.
 *
 * <p>{@code 043-space-memberships-subject.yaml} is applied per test method rather than folded into
 * the template: the backfill below reads {@code subject_type} and {@code group_id}, which only 043
 * creates, and a fixture file of its own is reserved for a changeset several others depend on (see
 * {@code test-master-through-baseline.yaml}).
 */
class Migration044SpaceMembershipHistoryTest extends AbstractMigrationTest {

  private static final String SUBJECT_CHANGELOG_PATH =
      "db/changelog/changes/043-space-memberships-subject.yaml";
  private static final String CHANGELOG_PATH =
      "db/changelog/changes/044-create-space-membership-history.yaml";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    applyChangelog(connection, SUBJECT_CHANGELOG_PATH);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void beforeTheChangesetThereIsNoSpaceMembershipHistoryAtAll() throws Exception {
    assertThat(tableExists("space_membership_history")).isFalse();
  }

  @Test
  void theChangesetCreatesTheTableWithTheColumnRulesOfAdr0016() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(tableExists("space_membership_history")).isTrue();
    assertThat(deleteRuleOf("fk_space_membership_history_subject_user_organization"))
        .as("person columns are RESTRICT")
        .isEqualTo("RESTRICT");
    assertThat(deleteRuleOf("fk_space_membership_history_actor_user_organization"))
        .as("the actor says nothing about a right and is cleared instead")
        .isEqualTo("SET NULL");
    assertThat(foreignKeyOn("space_membership_history", "space_id"))
        .as("object columns carry no foreign key - the history outlives its space")
        .isFalse();
    assertThat(foreignKeyOn("space_membership_history", "subject_group_id"))
        .as("a group is deletable; its history must survive that, like asset_grant_history")
        .isFalse();
    assertThat(indexDefinition("uk_space_membership_history_open_user"))
        .contains("(space_id, subject_user_id)")
        .contains("valid_to IS NULL");
    assertThat(indexDefinition("uk_space_membership_history_open_group"))
        .contains("(space_id, subject_group_id)")
        .contains("valid_to IS NULL");
  }

  /** ADR-0036/8: the bestand gets an open interval from the migration on, not an invented past. */
  @Test
  void everyExistingMembershipReceivesAnOpenBackfillInterval() throws Exception {
    Fixture fixture = seedSpaceWithAPersonAndAGroupMembership();

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(
            count(
                "SELECT count(*) FROM space_membership_history WHERE space_id = '"
                    + fixture.space()
                    + "' AND cause = 'BACKFILL' AND valid_to IS NULL"))
        .isEqualTo(2);
    assertThat(
            count(
                "SELECT count(*) FROM space_membership_history WHERE subject_type = 'USER'"
                    + " AND subject_user_id = '"
                    + fixture.owner()
                    + "' AND subject_group_id IS NULL AND role = 'ADMIN'"))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM space_membership_history WHERE subject_type = 'GROUP'"
                    + " AND subject_group_id = '"
                    + fixture.group()
                    + "' AND subject_user_id IS NULL AND member_count_at_grant = 23"))
        .isEqualTo(1);
  }

  /**
   * The condition under which an existing account stays deletable at all: nobody decides the
   * membership of the personal space, no path ever closes its interval ({@code
   * SpaceService#deleteSpace} refuses the default space, {@code LocalUserService} deletes it past
   * every history writer), and {@code subject_user_id} is {@code ON DELETE RESTRICT} - so an
   * interval here would make every account that ever signed in permanently undeletable.
   */
  @Test
  void thePersonalSpaceGetsNoBackfillIntervalAndItsOwnerStaysDeletable() throws Exception {
    Fixture fixture = seedSpaceWithAPersonAndAGroupMembership();

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(
            count(
                "SELECT count(*) FROM space_membership_history WHERE space_id = '"
                    + fixture.personalSpace()
                    + "'"))
        .isZero();

    // What the exclusion is for: after the account's own rows are gone, nothing of the personal
    // space is left to hold the users row back.
    execute("DELETE FROM space_memberships WHERE organization_id = ?", fixture.organization());
    execute("DELETE FROM space_membership_history WHERE space_id = ?", fixture.space());
    execute("DELETE FROM spaces WHERE organization_id = ?", fixture.organization());
    execute("DELETE FROM users WHERE id = ?", fixture.owner());

    assertThat(count("SELECT count(*) FROM users WHERE id = '" + fixture.owner() + "'")).isZero();
  }

  @Test
  void theSubjectCheckRejectsAMixedOrEmptySubject() throws Exception {
    Fixture fixture = seedSpaceWithAPersonAndAGroupMembership();

    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(
            () -> insertInterval(fixture, "USER", fixture.owner(), fixture.group(), "MEMBER", null))
        .hasMessageContaining("chk_space_membership_history_subject");
    assertThatThrownBy(() -> insertInterval(fixture, "GROUP", null, null, "MEMBER", null))
        .hasMessageContaining("chk_space_membership_history_subject");
  }

  /**
   * The partial unique index is what stops two concurrent transactions from leaving an interleaved
   * chain behind - the clock only orders the boundaries it hands out.
   */
  @Test
  void asubjectCannotHoldTwoOpenIntervalsOfTheSameSpace() throws Exception {
    Fixture fixture = seedSpaceWithAPersonAndAGroupMembership();

    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(
            () -> insertInterval(fixture, "USER", fixture.owner(), null, "CURATOR", null))
        .hasMessageContaining("uk_space_membership_history_open_user");
    // A closed interval beside the open one is exactly what a role change leaves behind.
    insertInterval(fixture, "USER", fixture.owner(), null, "CURATOR", Instant.now());
  }

  /** A zero-length marker records the removal itself and is exempt from the open-interval rule. */
  @Test
  void aZeroLengthMarkerIsAllowedBesideAnOpenInterval() throws Exception {
    Fixture fixture = seedSpaceWithAPersonAndAGroupMembership();

    applyChangelog(connection, CHANGELOG_PATH);

    Instant at = Instant.now();
    insertIntervalAt(fixture, "USER", fixture.owner(), null, "REMOVED", "ADMIN", at, at);

    assertThat(
            count(
                "SELECT count(*) FROM space_membership_history WHERE cause = 'REMOVED'"
                    + " AND valid_from = valid_to"))
        .isEqualTo(1);
  }

  @Test
  void aPersonReferencedByTheHistoryCannotBeDeleted() throws Exception {
    Fixture fixture = seedSpaceWithAPersonAndAGroupMembership();

    applyChangelog(connection, CHANGELOG_PATH);

    // Both spaces of the fixture, personal one included - otherwise the users row would be held
    // back by fk_spaces_owner and this would prove nothing about the history.
    execute("DELETE FROM space_memberships WHERE organization_id = ?", fixture.organization());
    execute("DELETE FROM spaces WHERE organization_id = ?", fixture.organization());

    assertThatThrownBy(() -> execute("DELETE FROM users WHERE id = ?", fixture.owner()))
        .hasMessageContaining("fk_space_membership_history_subject_user_organization");
  }

  @Test
  void theChangelogIsReferencedByTheMasterChangelog() throws Exception {
    assertThat(masterChangelog()).contains(CHANGELOG_PATH);
  }

  // -----------------------------------------------------------------------------------------
  // Fixture
  // -----------------------------------------------------------------------------------------

  private record Fixture(
      UUID organization, UUID owner, UUID space, UUID group, UUID personalSpace) {}

  private Fixture seedSpaceWithAPersonAndAGroupMembership() throws SQLException {
    UUID organization = seedOrganization();
    UUID owner = seedUser(organization);
    UUID space = seedSpace(organization, owner);
    UUID group = seedGroup(organization);
    execute(
        "INSERT INTO space_memberships (id, subject_type, user_id, space_id, role,"
            + " organization_id) VALUES (?, 'USER', ?, ?, 'ADMIN', ?)",
        UUID.randomUUID(),
        owner,
        space,
        organization);
    execute(
        "INSERT INTO space_memberships (id, subject_type, group_id, space_id, role,"
            + " organization_id, member_count_at_grant)"
            + " VALUES (?, 'GROUP', ?, ?, 'MEMBER', ?, 23)",
        UUID.randomUUID(),
        group,
        space,
        organization);
    // The personal space every account gets at first sign-in, with the membership row
    // SpaceRepository#insertDefaultSpaceIfAbsent writes for it.
    UUID personalSpace = seedPersonalSpace(organization, owner);
    execute(
        "INSERT INTO space_memberships (id, subject_type, user_id, space_id, role,"
            + " organization_id) VALUES (?, 'USER', ?, ?, 'ADMIN', ?)",
        UUID.randomUUID(),
        owner,
        personalSpace,
        organization);
    return new Fixture(organization, owner, space, group, personalSpace);
  }

  private void insertInterval(
      Fixture fixture, String subjectType, UUID user, UUID group, String role, Instant validTo)
      throws SQLException {
    insertIntervalAt(fixture, subjectType, user, group, "ADDED", role, Instant.now(), validTo);
  }

  private void insertIntervalAt(
      Fixture fixture,
      String subjectType,
      UUID user,
      UUID group,
      String cause,
      String role,
      Instant validFrom,
      Instant validTo)
      throws SQLException {
    execute(
        "INSERT INTO space_membership_history (id, space_id, organization_id, subject_type,"
            + " subject_user_id, subject_group_id, role, cause, valid_from, valid_to)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        fixture.space(),
        fixture.organization(),
        subjectType,
        user,
        group,
        role,
        cause,
        Timestamp.from(validFrom),
        validTo == null ? null : Timestamp.from(validTo));
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

  private UUID seedPersonalSpace(UUID organization, UUID owner) throws SQLException {
    UUID space = UUID.randomUUID();
    execute(
        "INSERT INTO spaces (id, name, owner_id, organization_id, visibility, is_default)"
            + " VALUES (?, 'Meine Dokumente', ?, ?, 'PRIVATE', true)",
        space,
        owner,
        organization);
    return space;
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

  private boolean tableExists(String table) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema()"
                + " AND table_name = ?")) {
      statement.setString(1, table);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }

  private boolean foreignKeyOn(String table, String column) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM pg_constraint fk"
                + " JOIN pg_class child ON child.oid = fk.conrelid"
                + " JOIN LATERAL unnest(fk.conkey) AS key_column(attnum) ON true"
                + " JOIN pg_attribute column_of_child"
                + "   ON column_of_child.attrelid = fk.conrelid"
                + "  AND column_of_child.attnum = key_column.attnum"
                + " WHERE fk.contype = 'f' AND child.relname = ?"
                + "   AND column_of_child.attname = ?")) {
      statement.setString(1, table);
      statement.setString(2, column);
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
