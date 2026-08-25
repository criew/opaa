package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The second of the two remaining {@code io.opaa.migration} tests after #904 (see {@link
 * MigrationBaselineTest}'s Javadoc for the general baseline-consolidation context): the audit
 * privilege model ADR-0015 describes - ownership of {@code audit_log}/{@code
 * audit_actor_pseudonyms}/{@code audit_retention_settings} moved to the restricted {@code
 * opaa_audit_owner} role, with the migration/application account left only the narrow grants the
 * baseline's group (f) establishes - no longer has a dedicated regression test of its own after the
 * deletion of {@code Migration017AuditLogTest}/{@code Migration023AuditRetentionTest} (~40 methods
 * between them). This class is deliberately not a full port: see the #904 pull request description
 * for the complete list of what those two classes additionally covered and why each omission is
 * considered acceptable (mostly: enum-value/CHECK-constraint content already duplicated by
 * application-level tests, and the documented, tracked CREATEROLE escalation residual ADR-0015/#426
 * describe as a known, open gap rather than a guarantee to regression-test).
 *
 * <p><b>Why this test does not re-run the baseline as a non-superuser role (unlike the deleted
 * classes' {@code AUDIT_APP_ROLE} dance):</b> {@link AbstractMigrationTest}'s template-database
 * mechanism always builds {@link #baseFixtureChangelogPath()} as the container's bootstrap
 * superuser - there is no supported way to make just this class's fixture build run as a different,
 * restricted role while every other class in this package keeps using the shared template
 * mechanism. Splitting {@code changes/001-baseline.yaml} to make that possible would violate #904's
 * own "genau eine Baseline-Datei" requirement. Instead, this test creates an ordinary, freshly
 * provisioned role after the baseline has already been applied and grants it exactly the privileges
 * the baseline's group (f) grants to {@code current_user} at migration time (INSERT/SELECT on
 * {@code audit_log}/{@code audit_actor_pseudonyms}, SELECT + narrow UPDATE on {@code
 * audit_retention_settings}) - the REVOKE/GRANT/ownership mechanism under test does not care which
 * role name ends up holding those grants, only that holding exactly them (and no more) blocks the
 * operations below. What this intentionally does not exercise is the specific CREATEROLE-time
 * residual membership ADR-0015 documents as a known, tracked escalation (#426) - that residual is a
 * property of the *migration* role's own CREATEROLE attribute, not of an arbitrary grantee, and is
 * out of scope for a smoke-level privilege check.
 */
class AuditPrivilegeModelTest extends AbstractMigrationTest {

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";
  private static final String OWNER_ROLE = "opaa_audit_owner";
  private static final String APP_ROLE = "audit_privilege_model_test_role";
  private static final String APP_ROLE_PASSWORD = "audit_privilege_model_test_password";

  private Connection bootstrapConnection;
  private Connection appConnection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/changes/001-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws SQLException {
    bootstrapConnection = connect();
    provisionApplicationRole();
    appConnection = connect(APP_ROLE, APP_ROLE_PASSWORD);
  }

  @AfterEach
  void tearDown() throws SQLException {
    appConnection.close();
    bootstrapConnection.close();
    // APP_ROLE is cluster-wide (see AbstractMigrationTest's Javadoc on cluster-wide roles) and
    // must be dropped per test method; the per-test database must go first since it is the only
    // database that ever granted this role anything.
    dropCurrentDatabaseNow();
    try (Connection admin = adminConnection();
        Statement statement = admin.createStatement()) {
      statement.execute("DROP ROLE IF EXISTS " + APP_ROLE);
    }
  }

  /**
   * Mirrors exactly what the baseline's group (f) grants to {@code current_user} at migration time
   * - see this class's own Javadoc for why a fresh role rather than re-running the migration as a
   * restricted account.
   */
  private void provisionApplicationRole() throws SQLException {
    dropRolesIfExist(bootstrapConnection, APP_ROLE);
    try (Statement statement = bootstrapConnection.createStatement()) {
      statement.execute("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_ROLE_PASSWORD + "'");
      statement.execute("GRANT INSERT, SELECT ON audit_log, audit_actor_pseudonyms TO " + APP_ROLE);
      statement.execute("GRANT SELECT ON audit_retention_settings TO " + APP_ROLE);
      statement.execute(
          "GRANT UPDATE (retention_months, updated_at) ON audit_retention_settings TO " + APP_ROLE);
    }
  }

  @Test
  void applicationAccountCannotModifyOrTruncateTheAuditLogParentTable() throws Exception {
    UUID eventId = insertMinimalEntry();

    assertThatThrownBy(() -> execute(appConnection, "UPDATE audit_log SET outcome = 'FAILURE'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
    assertThatThrownBy(
            () ->
                execute(appConnection, "DELETE FROM audit_log WHERE event_id = '" + eventId + "'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
    assertThatThrownBy(() -> execute(appConnection, "TRUNCATE TABLE audit_log"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
  }

  /**
   * Every Postgres partition carries its own ACL - a parent-table-only REVOKE/GRANT does not by
   * itself protect a partition addressed directly by name (ADR-0015).
   */
  @Test
  void applicationAccountCannotModifyOrTruncateAPartitionDirectly() throws Exception {
    UUID eventId = insertMinimalEntry();
    String partition = partitionNameOf(eventId);

    assertThatThrownBy(
            () -> execute(appConnection, "UPDATE " + partition + " SET outcome = 'FAILURE'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
    assertThatThrownBy(
            () ->
                execute(
                    appConnection,
                    "DELETE FROM " + partition + " WHERE event_id = '" + eventId + "'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
    assertThatThrownBy(() -> execute(appConnection, "TRUNCATE TABLE " + partition))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
  }

  /**
   * "GRANT ALL ... TO <no grant option>" is a Postgres quirk: unlike a single-privilege GRANT, a
   * grantor with no grantable privilege at all does not raise a hard error - it silently grants
   * nothing (a non-fatal WARNING the JDBC driver does not surface). So this asserts on the actual
   * effect (the write restriction still holds afterwards) rather than on the GRANT statement itself
   * throwing.
   */
  @Test
  void applicationAccountCannotGrantItselfAdditionalPrivileges() throws Exception {
    UUID eventId = insertMinimalEntry();
    try (Statement statement = appConnection.createStatement()) {
      statement.execute("GRANT ALL ON TABLE audit_log TO " + APP_ROLE);
    } catch (SQLException expectedOrIgnored) {
      // Either outcome is acceptable - see this method's own Javadoc.
    }

    assertThatThrownBy(
            () ->
                execute(appConnection, "DELETE FROM audit_log WHERE event_id = '" + eventId + "'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
  }

  @Test
  void applicationAccountCannotSwitchItsSessionIdentityToTheOwnerRole() throws Exception {
    assertThatThrownBy(() -> execute(appConnection, "SET ROLE " + OWNER_ROLE))
        .isInstanceOf(SQLException.class);
  }

  @Test
  void applicationAccountCanNeitherDropACheckConstraintNorDetachAPartition() throws Exception {
    UUID eventId = insertMinimalEntry();
    String partition = partitionNameOf(eventId);

    assertThatThrownBy(
            () ->
                execute(
                    appConnection, "ALTER TABLE audit_log DROP CONSTRAINT chk_audit_log_subject"))
        .isInstanceOf(SQLException.class);
    assertThatThrownBy(
            () -> execute(appConnection, "ALTER TABLE audit_log DETACH PARTITION " + partition))
        .isInstanceOf(SQLException.class);
  }

  @Test
  void auditRetentionSettingsAcceptsExactlyOneRow() throws Exception {
    // Written with the bootstrap (superuser) connection deliberately - a CHECK constraint binds
    // every role, including a superuser, so this proves the schema-level singleton guarantee
    // itself, independent of the ACL restriction the other tests in this class cover.
    assertThatThrownBy(
            () ->
                execute(
                    bootstrapConnection,
                    "INSERT INTO audit_retention_settings (id, retention_months, updated_at)"
                        + " VALUES (2, 36, now())"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_audit_retention_settings_singleton");
  }

  /**
   * The application account's grant on {@code audit_retention_settings} is narrowed to {@code
   * (retention_months, updated_at)} (baseline group (f), mirroring migration 023) - {@code
   * last_cutoff} is written exclusively by {@code opaa_audit_delete_expired_partitions()}, which is
   * what makes that function's own forward-only cap an actual guarantee rather than a convention
   * the application could bypass by writing the column directly.
   */
  @Test
  void applicationAccountCanUpdateRetentionMonthsButNotLastCutoffDirectly() throws Exception {
    execute(
        appConnection, "UPDATE audit_retention_settings SET retention_months = 24 WHERE id = 1");

    assertThatThrownBy(
            () ->
                execute(
                    appConnection,
                    "UPDATE audit_retention_settings SET last_cutoff = now() WHERE id = 1"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
  }

  @Test
  void aWriteWithinTheAuditLogPartitionHorizonSucceeds() throws Exception {
    Instant fifteenYearsOut = Instant.now().plus(15 * 365, ChronoUnit.DAYS);

    UUID eventId = insertEntryAt(fifteenYearsOut);

    try (Statement statement = appConnection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM audit_log WHERE event_id = '" + eventId + "'")) {
      result.next();
      assertThat(result.getInt(1)).isEqualTo(1);
    }
  }

  @Test
  void aWriteBeyondTheAuditLogPartitionHorizonFailsHardInsteadOfLandingInAnUnreclaimablePartition()
      throws Exception {
    Instant twentyYearsOut = Instant.now().plus(20 * 365, ChronoUnit.DAYS);

    assertThatThrownBy(() -> insertEntryAt(twentyYearsOut))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("no partition of relation");
  }

  private void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private UUID insertMinimalEntry() throws SQLException {
    return insertEntryAt(Instant.now());
  }

  private UUID insertEntryAt(Instant recordedAt) throws SQLException {
    UUID eventId = UUID.randomUUID();
    try (Statement statement = appConnection.createStatement()) {
      statement.execute(
          "INSERT INTO audit_log (event_id, recorded_at, organization_id, actor_kind, actor_ref,"
              + " event_type, object_type, object_id, outcome) VALUES ('"
              + eventId
              + "', '"
              + recordedAt
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', 'USER', 'pseud-actor-1', 'SPACE_CREATED', 'SPACE', 'space-1', 'SUCCESS')");
    }
    return eventId;
  }

  /** The physical partition table an already-written entry actually landed in, via tableoid. */
  private String partitionNameOf(UUID eventId) throws SQLException {
    try (Statement statement = bootstrapConnection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT tableoid::regclass::text FROM audit_log WHERE event_id = '"
                    + eventId
                    + "'")) {
      result.next();
      return result.getString(1);
    }
  }
}
