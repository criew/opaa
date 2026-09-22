package io.opaa.migration;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

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
 * Delta tests for {@code changes/076-backfill-library-ownership-history.yaml} (#1819, ADR-0036
 * Entscheidung 8): the stock entry changeset 052 deliberately left to this issue - every library
 * that existed gets one open ownership interval, and one that already has one keeps it.
 */
class Migration076LibraryOwnershipBackfillTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/076-backfill-library-ownership-history.yaml";

  private static final String[] PREREQUISITES = {
    "db/changelog/changes/041-groups-provider-origin.yaml",
    "db/changelog/changes/052-create-asset-ownership-history.yaml",
  };

  private static final UUID DEFAULT_ORGANIZATION =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

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
  void everyLibraryOfTheStockGetsOneOpenInterval() throws Exception {
    applyPrerequisites();
    UUID owner = seedUser();
    UUID group = seedGroup();
    UUID personOwned = seedLibrary("USER", owner, null);
    UUID groupOwned = seedLibrary("GROUP", null, group);

    assertThat(intervalsOf(personOwned)).isZero();

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(intervalsOf(personOwned)).isEqualTo(1);
    assertThat(intervalsOf(groupOwned)).isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM asset_ownership_history WHERE asset_id = '"
                    + groupOwned
                    + "' AND owner_group_id = '"
                    + group
                    + "' AND cause = 'BACKFILL' AND valid_to IS NULL"))
        .as("a group-owned library carries the group, as chk_asset_ownership_history_owner demands")
        .isEqualTo(1);
  }

  /** Repeatable: a library that already carries an open interval gets no second one. */
  @Test
  void aLibraryThatAlreadyHasAnIntervalIsLeftAlone() throws Exception {
    applyPrerequisites();
    UUID owner = seedUser();
    UUID library = seedLibrary("USER", owner, null);
    execute(
        "INSERT INTO asset_ownership_history (id, asset_type, asset_id, organization_id,"
            + " owner_type, owner_user_id, cause, valid_from) VALUES (?, 'KNOWLEDGE_LIBRARY', ?,"
            + " ?, 'USER', ?, 'CREATED', now())",
        UUID.randomUUID(),
        library,
        DEFAULT_ORGANIZATION,
        owner);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(intervalsOf(library)).isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM asset_ownership_history WHERE asset_id = '"
                    + library
                    + "' AND cause = 'CREATED'"))
        .isEqualTo(1);
  }

  @Test
  void theChangelogIsReferencedByTheMasterChangelog() throws Exception {
    String master =
        new String(
            requireNonNull(
                    getClass()
                        .getClassLoader()
                        .getResourceAsStream("db/changelog/db.changelog-master.yaml"))
                .readAllBytes(),
            StandardCharsets.UTF_8);

    assertThat(master).contains(CHANGELOG_PATH);
  }

  // -----------------------------------------------------------------------------------------
  // Fixture
  // -----------------------------------------------------------------------------------------

  private void applyPrerequisites() throws Exception {
    for (String path : PREREQUISITES) {
      applyChangelog(connection, path);
    }
  }

  private long intervalsOf(UUID libraryId) throws SQLException {
    return count(
        "SELECT count(*) FROM asset_ownership_history WHERE asset_type = 'KNOWLEDGE_LIBRARY'"
            + " AND asset_id = '"
            + libraryId
            + "' AND valid_to IS NULL");
  }

  private UUID seedLibrary(String ownerType, UUID ownerUserId, UUID ownerGroupId)
      throws SQLException {
    UUID library = UUID.randomUUID();
    execute(
        "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type, owner_user_id,"
            + " owner_group_id, visibility, listed, source_type, created_at, updated_at) VALUES"
            + " (?, ?, ?, ?, ?, ?, 'PRIVATE', false, 'UPLOAD', now(), now())",
        library,
        DEFAULT_ORGANIZATION,
        "Bibliothek " + library,
        ownerType,
        ownerUserId,
        ownerGroupId);
    return library;
  }

  private UUID seedUser() throws SQLException {
    UUID user = UUID.randomUUID();
    execute(
        "INSERT INTO users (id, subject, issuer, email, organization_id) VALUES (?, ?,"
            + " 'https://issuer.example.org', ?, ?)",
        user,
        user.toString(),
        user + "@example.org",
        DEFAULT_ORGANIZATION);
    return user;
  }

  private UUID seedGroup() throws SQLException {
    UUID group = UUID.randomUUID();
    execute(
        "INSERT INTO groups (id, organization_id, kind, name) VALUES (?, ?, 'AD_HOC', 'Referat"
            + " 50')",
        group,
        DEFAULT_ORGANIZATION);
    return group;
  }

  private void execute(String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      statement.executeUpdate();
    }
  }

  private long count(String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }
}
