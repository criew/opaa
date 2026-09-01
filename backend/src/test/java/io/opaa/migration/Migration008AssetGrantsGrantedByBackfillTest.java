package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta test for {@code changes/008-asset-grants-granted-by-backfill.yaml} (#1052): {@code
 * asset_grants.granted_by_user_id} must name whoever conferred the role the row carries now, for
 * rows whose role was already raised before the application started writing that meaning. The
 * changeSet reconstructs it from {@code asset_grant_history}; these tests pin both directions - it
 * rewrites a row whose role a foreign actor raised, and it leaves every row alone whose history
 * does not actually say who conferred the current role.
 */
class Migration008AssetGrantsGrantedByBackfillTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/008-asset-grants-granted-by-backfill.yaml";
  private static final UUID ORGANIZATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final Instant BASE = Instant.parse("2026-03-01T10:00:00Z");

  private Connection connection;
  private UUID originalGranter;
  private UUID raisingAdmin;
  private UUID subjectUser;
  private UUID libraryId;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    originalGranter = insertUser("stelle-a");
    raisingAdmin = insertUser("admin-b");
    subjectUser = insertUser("subject");
    libraryId = insertLibrary(originalGranter);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  /**
   * The attack path the field exists for: a grant somebody else issued as {@code VIEWER}, raised to
   * {@code OWNER} by an administrator through the system-admin floor. Before the backfill the row
   * still names the original granter, so {@code holdsIndependentOwnerRole} treats the self-procured
   * {@code OWNER} as foreign-issued and lets that administrator lift a foreign diagnostics lock.
   */
  @Test
  void namesTheActorWhoRaisedTheRole() throws Exception {
    UUID grantId = insertGrant("OWNER", originalGranter);
    insertHistory("VIEWER", "GRANTED", originalGranter, BASE, BASE.plus(1, ChronoUnit.DAYS));
    insertHistory("OWNER", "ROLE_CHANGED", raisingAdmin, BASE.plus(1, ChronoUnit.DAYS), null);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(grantedBy(grantId)).isEqualTo(raisingAdmin);
  }

  @Test
  void theYoungestRoleChangeWins() throws Exception {
    UUID thirdActor = insertUser("admin-c");
    UUID grantId = insertGrant("OWNER", originalGranter);
    insertHistory("VIEWER", "GRANTED", originalGranter, BASE, BASE.plus(1, ChronoUnit.DAYS));
    insertHistory(
        "EDITOR",
        "ROLE_CHANGED",
        raisingAdmin,
        BASE.plus(1, ChronoUnit.DAYS),
        BASE.plus(2, ChronoUnit.DAYS));
    insertHistory("OWNER", "ROLE_CHANGED", thirdActor, BASE.plus(2, ChronoUnit.DAYS), null);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(grantedBy(grantId)).isEqualTo(thirdActor);
  }

  @Test
  void leavesAGrantWithoutAnyHistoryUntouched() throws Exception {
    UUID grantId = insertGrant("OWNER", originalGranter);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(grantedBy(grantId)).isEqualTo(originalGranter);
  }

  @Test
  void leavesAGrantWhoseRoleWasNeverChangedUntouched() throws Exception {
    UUID grantId = insertGrant("OWNER", originalGranter);
    insertHistory("OWNER", "GRANTED", originalGranter, BASE, null);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(grantedBy(grantId)).isEqualTo(originalGranter);
  }

  /**
   * A pure expiry change writes a {@code ROLE_CHANGED} interval too, but does not move the role -
   * and must not move the conferrer either, or an owner extending their own grant would become its
   * own conferrer and lose the right to lift a lock.
   */
  @Test
  void ignoresAnIntervalThatOnlyChangedTheExpiry() throws Exception {
    UUID grantId = insertGrant("OWNER", originalGranter);
    insertHistory("OWNER", "GRANTED", originalGranter, BASE, BASE.plus(1, ChronoUnit.DAYS));
    insertHistory("OWNER", "ROLE_CHANGED", subjectUser, BASE.plus(1, ChronoUnit.DAYS), null);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(grantedBy(grantId)).isEqualTo(originalGranter);
  }

  /**
   * History of a revoked grant belongs to a row that no longer exists. A later grant to the same
   * subject on the same library is its own episode, and the role change from before the revocation
   * must not reach into it.
   */
  @Test
  void ignoresHistoryFromBeforeARevocation() throws Exception {
    UUID grantId = insertGrant("OWNER", originalGranter);
    insertHistory("VIEWER", "GRANTED", originalGranter, BASE, BASE.plus(1, ChronoUnit.DAYS));
    insertHistory(
        "OWNER",
        "ROLE_CHANGED",
        raisingAdmin,
        BASE.plus(1, ChronoUnit.DAYS),
        BASE.plus(2, ChronoUnit.DAYS));
    insertHistory(
        "OWNER",
        "REVOKED",
        raisingAdmin,
        BASE.plus(2, ChronoUnit.DAYS),
        BASE.plus(2, ChronoUnit.DAYS));
    insertHistory("OWNER", "GRANTED", originalGranter, BASE.plus(3, ChronoUnit.DAYS), null);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(grantedBy(grantId)).isEqualTo(originalGranter);
  }

  private UUID grantedBy(UUID grantId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT granted_by_user_id FROM asset_grants WHERE id = ?")) {
      statement.setObject(1, grantId);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).as("grant %s must exist", grantId).isTrue();
        return rs.getObject("granted_by_user_id", UUID.class);
      }
    }
  }

  private UUID insertGrant(String role, UUID grantedByUserId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO asset_grants (id, library_id, organization_id, subject_type,"
                + " subject_user_id, role, granted_by_user_id) VALUES (?, ?, ?, 'USER', ?, ?, ?)")) {
      statement.setObject(1, id);
      statement.setObject(2, libraryId);
      statement.setObject(3, ORGANIZATION_ID);
      statement.setObject(4, subjectUser);
      statement.setString(5, role);
      statement.setObject(6, grantedByUserId);
      statement.executeUpdate();
    }
    return id;
  }

  private void insertHistory(
      String role, String cause, UUID actorUserId, Instant validFrom, Instant validTo)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO asset_grant_history (id, library_id, organization_id, subject_type,"
                + " subject_user_id, role, cause, actor_user_id, valid_from, valid_to)"
                + " VALUES (?, ?, ?, 'USER', ?, ?, ?, ?, ?, ?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, libraryId);
      statement.setObject(3, ORGANIZATION_ID);
      statement.setObject(4, subjectUser);
      statement.setString(5, role);
      statement.setString(6, cause);
      statement.setObject(7, actorUserId);
      statement.setObject(8, Timestamp.from(validFrom));
      statement.setObject(9, validTo == null ? null : Timestamp.from(validTo));
      statement.executeUpdate();
    }
  }

  private UUID insertUser(String subject) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO users (id, subject, issuer, organization_id) VALUES (?, ?, 'test', ?)")) {
      statement.setObject(1, id);
      statement.setString(2, subject + "-" + id);
      statement.setObject(3, ORGANIZATION_ID);
      statement.executeUpdate();
    }
    return id;
  }

  private UUID insertLibrary(UUID ownerUserId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type,"
                + " owner_user_id, visibility, source_type)"
                + " VALUES (?, ?, ?, 'USER', ?, 'PRIVATE', 'UPLOAD')")) {
      statement.setObject(1, id);
      statement.setObject(2, ORGANIZATION_ID);
      statement.setString(3, "Bibliothek " + id);
      statement.setObject(4, ownerUserId);
      statement.executeUpdate();
    }
    return id;
  }
}
