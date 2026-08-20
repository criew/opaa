package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.audit.AuditEventType;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies Liquibase changelog 040 in isolation, on top of the real changelog chain 017 -&gt; 022
 * -&gt; 035 - the newest widen already merged to main at the time this migration was written
 * (#545's {@code LIBRARY_SOURCE_UPDATED}) - the same restricted-role pattern {@code
 * Migration022AuditorRoleEventTypesTest} establishes (see that class's Javadoc for the full
 * reasoning why a non-superuser {@code AUDIT_APP_ROLE} is required to actually exercise 040's own
 * {@code SET ROLE opaa_audit_owner} step).
 *
 * <p>#613 review, finding 1: an earlier version of this test applied only 017 and 022, skipping 035
 * - the exact gap that let 040's own CHECK list silently drop {@code LIBRARY_SOURCE_UPDATED}
 * (rebuilt from the 022 state instead of the current one) without any test catching it. This class
 * now chains through every migration that has actually widened the constraint before 040, and
 * {@link #everyCurrentEventTypeIsAcceptedAfterTheWideningMigration()} inserts <em>every</em> {@link
 * AuditEventType} value - not a representative sample - so a future rebuild-from-a-stale- base
 * mistake fails on the very value it drops, rather than possibly missing it if that value is not
 * among the ones a sample happens to cover.
 *
 * <p>Proves, against a real database rather than only the Java enum, that {@code
 * chk_audit_log_event_type} accepts {@code SPACE_ARCHIVED} after 040 runs, and that every value
 * accepted before 040 - the complete {@link AuditEventType} set minus {@code SPACE_ARCHIVED} itself
 * - is still accepted afterwards (a widen must never accidentally narrow).
 */
class Migration040SpaceArchivedEventTypeTest extends AbstractMigrationTest {

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";
  private static final String AUDIT_APP_ROLE = "audit_app_role";
  private static final String AUDIT_APP_ROLE_PASSWORD = "audit_app_role_password";
  private static final String OWNER_ROLE = "opaa_audit_owner";

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-016.yaml";
  }

  private Connection bootstrapConnection;
  private Connection appConnection;

  @BeforeEach
  void setUp() throws Exception {
    bootstrapConnection = connect();

    createNonSuperuserApplicationRole();

    appConnection = connect(AUDIT_APP_ROLE, AUDIT_APP_ROLE_PASSWORD);
    applyChangelog(appConnection, "db/changelog/changes/017-audit-log.yaml");
    applyChangelog(
        appConnection, "db/changelog/changes/022-widen-audit-event-type-auditor-role.yaml");
    applyChangelog(
        appConnection,
        "db/changelog/changes/035-widen-audit-event-type-library-source-updated.yaml");
    applyChangelog(
        appConnection, "db/changelog/changes/040-widen-audit-event-type-space-archived.yaml");
  }

  @AfterEach
  void tearDown() throws SQLException {
    appConnection.close();
    // See Migration017AuditLogTest#tearDown() for why the database is dropped before the
    // cluster-wide roles, and via a fresh admin connection rather than bootstrapConnection.
    bootstrapConnection.close();
    dropCurrentDatabaseNow();
    try (Connection admin = adminConnection();
        Statement statement = admin.createStatement()) {
      statement.execute("DROP ROLE IF EXISTS " + AUDIT_APP_ROLE);
      statement.execute("DROP ROLE IF EXISTS " + OWNER_ROLE);
    }
  }

  private void createNonSuperuserApplicationRole() throws SQLException {
    // Defensive cleanup (issue #497): AUDIT_APP_ROLE/OWNER_ROLE are cluster-wide role names this
    // class shares with other migration tests against the same singleton container - see
    // AbstractMigrationTest#dropRolesIfExist(...).
    dropRolesIfExist(bootstrapConnection, AUDIT_APP_ROLE, OWNER_ROLE);
    try (Statement statement = bootstrapConnection.createStatement()) {
      statement.execute(
          "CREATE ROLE "
              + AUDIT_APP_ROLE
              + " LOGIN CREATEROLE PASSWORD '"
              + AUDIT_APP_ROLE_PASSWORD
              + "'");
      statement.execute(
          "GRANT CREATE ON SCHEMA public TO " + AUDIT_APP_ROLE + " WITH GRANT OPTION");
      statement.execute("GRANT REFERENCES ON organizations TO " + AUDIT_APP_ROLE);
      statement.execute("GRANT REFERENCES ON users TO " + AUDIT_APP_ROLE);
      statement.execute(
          "GRANT SELECT, INSERT, UPDATE, DELETE ON databasechangelog, databasechangeloglock TO "
              + AUDIT_APP_ROLE);
    }
  }

  /**
   * #613 review, finding 1: exhaustive, not a sample - every {@link AuditEventType} value,
   * including {@code SPACE_ARCHIVED} itself, must round-trip through the widened constraint.
   * Deliberately not restricted to "pre-existing" values: the whole point is that nothing in the
   * current enum may be missing from the CHECK list 040 installs.
   */
  @Test
  void everyCurrentEventTypeIsAcceptedAfterTheWideningMigration() throws Exception {
    for (AuditEventType eventType : AuditEventType.values()) {
      UUID eventId = insertEntry(eventType.name());
      assertThat(eventExists(eventId))
          .as("event_type %s accepted after 040", eventType.name())
          .isTrue();
    }
  }

  @Test
  void aValueOutsideTheWidenedSetIsStillRejected() throws Exception {
    assertThatThrownBy(() -> insertEntry("NOT_A_REAL_EVENT_TYPE"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_audit_log_event_type");
  }

  private UUID insertEntry(String eventType) throws SQLException {
    UUID eventId = UUID.randomUUID();
    try (Statement statement = appConnection.createStatement()) {
      statement.execute(
          "INSERT INTO audit_log (event_id, recorded_at, organization_id, actor_kind, actor_ref,"
              + " event_type, object_type, object_id, outcome) VALUES ('"
              + eventId
              + "', now(), '"
              + SEEDED_ORGANIZATION_ID
              + "', 'USER', 'pseud-actor-1', '"
              + eventType
              + "', 'USER_ACCOUNT', 'pseud-subject-1', 'SUCCESS')");
    }
    return eventId;
  }

  private boolean eventExists(UUID eventId) throws SQLException {
    try (Statement statement = appConnection.createStatement();
        var result =
            statement.executeQuery(
                "SELECT count(*) FROM audit_log WHERE event_id = '" + eventId + "'")) {
      result.next();
      return result.getInt(1) == 1;
    }
  }
}
