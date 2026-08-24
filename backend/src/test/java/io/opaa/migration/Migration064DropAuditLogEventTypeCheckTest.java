package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies Liquibase changelog 064 in isolation, on top of 017 - the same restricted-role pattern
 * {@code Migration022AuditorRoleEventTypesTest}/{@code
 * Migration063AuditLogTimeRangeAndIncidentScopeIndexesTest} establish: {@code audit_log} is owned
 * by {@code opaa_audit_owner} after 017, not by the migration/application account, so this must run
 * as a non-superuser {@code AUDIT_APP_ROLE}.
 *
 * <p>Proves #862's acceptance criteria against a real database: {@code chk_audit_log_event_type} no
 * longer exists after 064 runs, a value outside the old closed list is now writable, and the
 * application account's grants (INSERT/SELECT only) are unchanged.
 */
class Migration064DropAuditLogEventTypeCheckTest extends AbstractMigrationTest {

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
    applyChangelog(appConnection, "db/changelog/changes/064-drop-audit-log-event-type-check.yaml");
  }

  @AfterEach
  void tearDown() throws SQLException {
    if (appConnection != null) {
      appConnection.close();
    }
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
    // class shares with the other audit_log migration test classes against the same singleton
    // container - see AbstractMigrationTest#dropRolesIfExist(...).
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

  @Test
  void theCheckConstraintNoLongerExists() throws Exception {
    assertThat(constraintExists("chk_audit_log_event_type")).isFalse();
  }

  @Test
  void aValueOutsideTheFormerClosedListIsNowWritable() throws Exception {
    UUID eventId = insertEntry("NOT_A_REAL_EVENT_TYPE");

    assertThat(eventExists(eventId)).isTrue();
  }

  @Test
  void everyPreExistingEventTypeIsStillAccepted() throws Exception {
    for (String eventType :
        new String[] {
          "ASSET_GRANT_GRANTED",
          "SPACE_CREATED",
          "SYSTEM_ADMIN_ROLE_GRANTED",
          "AUDITOR_ROLE_GRANTED",
          "GOVERNANCE_SETTINGS_CHANGED",
          "AUDIT_LOG_ACCESSED"
        }) {
      UUID eventId = insertEntry(eventType);
      assertThat(eventExists(eventId)).as("event_type %s still accepted", eventType).isTrue();
    }
  }

  @Test
  void theApplicationAccountCanStillOnlyInsertAndSelectAfter064() throws Exception {
    // The temporary SET TRUE / SET ROLE bracket 064 uses to reach the DROP CONSTRAINT must not
    // leave the application account with any lingering elevated privilege afterwards - the same
    // guarantee Migration022AuditorRoleEventTypesTest/Migration063...Test prove for their own DDL.
    UUID eventId = insertEntry("SPACE_CREATED");

    assertThatThrownBy(
            () -> {
              try (Statement statement = appConnection.createStatement()) {
                statement.execute(
                    "UPDATE audit_log SET outcome = 'FAILURE' WHERE event_id = '" + eventId + "'");
              }
            })
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
  }

  @Test
  void theApplicationAccountCannotSwitchItsSessionIdentityToTheOwnerRoleAfter064()
      throws Exception {
    assertThatThrownBy(
            () -> {
              try (Statement statement = appConnection.createStatement()) {
                statement.execute("SET ROLE " + OWNER_ROLE);
              }
            })
        .isInstanceOf(SQLException.class);
  }

  private boolean constraintExists(String constraintName) throws SQLException {
    try (Statement statement = bootstrapConnection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM pg_constraint WHERE conname = '" + constraintName + "'")) {
      result.next();
      return result.getInt(1) > 0;
    }
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
