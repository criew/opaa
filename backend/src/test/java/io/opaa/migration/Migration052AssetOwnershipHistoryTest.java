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
 * Delta tests for {@code changes/052-create-asset-ownership-history.yaml} (#1815, ADR-0036
 * Entscheidung 8): the ownership history, type-independent like {@code asset_grants}, with the
 * spaces of the installation as its first - and so far only - backfilled type.
 */
class Migration052AssetOwnershipHistoryTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/052-create-asset-ownership-history.yaml";

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
  void beforeTheChangesetThereIsNoOwnershipHistoryAtAll() throws Exception {
    assertThat(tableExists("asset_ownership_history")).isFalse();
  }

  @Test
  void theChangesetCreatesTheTableWithTheColumnRulesOfAdr0016() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(tableExists("asset_ownership_history")).isTrue();
    assertThat(deleteRuleOf("fk_asset_ownership_history_owner_user_organization"))
        .isEqualTo("RESTRICT");
    assertThat(deleteRuleOf("fk_asset_ownership_history_actor_user_organization"))
        .isEqualTo("SET NULL");
    assertThat(foreignKeyOn("asset_ownership_history", "asset_id"))
        .as("a type-independent object column can carry no foreign key at all")
        .isFalse();
    assertThat(foreignKeyOn("asset_ownership_history", "owner_group_id"))
        .as("a group is deletable; its ownership history must survive that")
        .isFalse();
    assertThat(indexDefinition("uk_asset_ownership_history_open"))
        .contains("(asset_type, asset_id)")
        .contains("valid_to IS NULL");
  }

  @Test
  void everyExistingSpaceReceivesAnOpenBackfillInterval() throws Exception {
    Fixture fixture = seedTwoSpaces();

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(
            count(
                "SELECT count(*) FROM asset_ownership_history WHERE asset_type = 'SPACE'"
                    + " AND cause = 'BACKFILL' AND valid_to IS NULL AND owner_type = 'USER'"))
        .as("the two ordinary spaces of the fixture, never its personal one")
        .isEqualTo(2);
    assertThat(
            count(
                "SELECT count(*) FROM asset_ownership_history WHERE asset_id = '"
                    + fixture.firstSpace()
                    + "' AND owner_user_id = '"
                    + fixture.owner()
                    + "'"))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM asset_ownership_history WHERE asset_type <> 'SPACE'"))
        .as("the library side of the table comes with its writer in #1819")
        .isZero();
  }

  /**
   * The condition under which an existing account stays deletable at all - see {@code
   * Migration044SpaceMembershipHistoryTest#thePersonalSpaceGetsNoBackfillIntervalAndItsOwnerStays
   * Deletable} for the same rule on the membership side: nobody decides the ownership of the
   * personal space, no path ever closes its interval, and {@code owner_user_id} is {@code ON DELETE
   * RESTRICT}.
   */
  @Test
  void thePersonalSpaceGetsNoBackfillIntervalAndItsOwnerStaysDeletable() throws Exception {
    Fixture fixture = seedTwoSpaces();

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(
            count(
                "SELECT count(*) FROM asset_ownership_history WHERE asset_id = '"
                    + fixture.personalSpace()
                    + "'"))
        .isZero();

    execute("DELETE FROM asset_ownership_history WHERE asset_id = ?", fixture.firstSpace());
    execute("DELETE FROM space_memberships WHERE organization_id = ?", fixture.organization());
    execute("DELETE FROM spaces WHERE owner_id = ?", fixture.owner());
    execute("DELETE FROM users WHERE id = ?", fixture.owner());

    assertThat(count("SELECT count(*) FROM users WHERE id = '" + fixture.owner() + "'")).isZero();
  }

  @Test
  void theFormCheckRejectsAnAssetTypeAssetTypeItselfWouldNotAccept() throws Exception {
    Fixture fixture = seedTwoSpaces();

    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO asset_ownership_history (id, asset_type, asset_id,"
                        + " organization_id, owner_type, owner_user_id, cause, valid_from)"
                        + " VALUES (?, 'space', ?, ?, 'USER', ?, 'CREATED', now())",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    fixture.organization(),
                    fixture.owner()))
        .hasMessageContaining("chk_asset_ownership_history_asset_type_format");
  }

  @Test
  void theOwnerCheckRejectsAMixedOrEmptyOwner() throws Exception {
    Fixture fixture = seedTwoSpaces();

    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO asset_ownership_history (id, asset_type, asset_id,"
                        + " organization_id, owner_type, owner_user_id, owner_group_id, cause,"
                        + " valid_from) VALUES (?, 'SPACE', ?, ?, 'USER', ?, ?, 'CREATED', now())",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    fixture.organization(),
                    fixture.owner(),
                    fixture.group()))
        .hasMessageContaining("chk_asset_ownership_history_owner");
    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO asset_ownership_history (id, asset_type, asset_id,"
                        + " organization_id, owner_type, cause, valid_from)"
                        + " VALUES (?, 'SPACE', ?, ?, 'GROUP', 'CREATED', now())",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    fixture.organization()))
        .hasMessageContaining("chk_asset_ownership_history_owner");
  }

  @Test
  void anAssetCannotHoldTwoOpenOwnershipIntervals() throws Exception {
    Fixture fixture = seedTwoSpaces();

    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(() -> insertOpenInterval(fixture, fixture.firstSpace()))
        .hasMessageContaining("uk_asset_ownership_history_open");
    // The same asset id under a different type is a different object and must not collide.
    execute(
        "INSERT INTO asset_ownership_history (id, asset_type, asset_id, organization_id,"
            + " owner_type, owner_user_id, cause, valid_from)"
            + " VALUES (?, 'KNOWLEDGE_LIBRARY', ?, ?, 'USER', ?, 'CREATED', ?)",
        UUID.randomUUID(),
        fixture.firstSpace(),
        fixture.organization(),
        fixture.owner(),
        Timestamp.from(Instant.now()));
  }

  @Test
  void anOwnerReferencedByTheHistoryCannotBeDeleted() throws Exception {
    Fixture fixture = seedTwoSpaces();

    applyChangelog(connection, CHANGELOG_PATH);

    execute("DELETE FROM space_memberships WHERE organization_id = ?", fixture.organization());
    execute("DELETE FROM spaces WHERE organization_id = ?", fixture.organization());

    assertThatThrownBy(() -> execute("DELETE FROM users WHERE id = ?", fixture.owner()))
        .hasMessageContaining("fk_asset_ownership_history_owner_user_organization");
  }

  @Test
  void theChangelogIsReferencedByTheMasterChangelog() throws Exception {
    assertThat(masterChangelog()).contains(CHANGELOG_PATH);
  }

  // -----------------------------------------------------------------------------------------
  // Fixture
  // -----------------------------------------------------------------------------------------

  private record Fixture(
      UUID organization, UUID owner, UUID firstSpace, UUID group, UUID personalSpace) {}

  private Fixture seedTwoSpaces() throws SQLException {
    UUID organization = seedOrganization();
    UUID owner = seedUser(organization);
    UUID first = seedSpace(organization, owner);
    seedSpace(organization, seedUser(organization));
    // The personal space every account gets at first sign-in.
    UUID personalSpace = seedPersonalSpace(organization, owner);
    return new Fixture(organization, owner, first, seedGroup(organization), personalSpace);
  }

  private void insertOpenInterval(Fixture fixture, UUID assetId) throws SQLException {
    execute(
        "INSERT INTO asset_ownership_history (id, asset_type, asset_id, organization_id,"
            + " owner_type, owner_user_id, cause, valid_from)"
            + " VALUES (?, 'SPACE', ?, ?, 'USER', ?, 'TRANSFERRED', ?)",
        UUID.randomUUID(),
        assetId,
        fixture.organization(),
        fixture.owner(),
        Timestamp.from(Instant.now()));
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
