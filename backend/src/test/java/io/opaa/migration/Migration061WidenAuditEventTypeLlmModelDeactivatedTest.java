package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies Liquibase changelog 061 in isolation, on top of 017, 035, 040, 042, 053 and 059 - the
 * same restricted-role pattern {@code Migration059WidenAuditEventTypeLlmModelChangesTest}
 * establishes (see its own Javadoc for the full reasoning): {@code audit_log} is owned by {@code
 * opaa_audit_owner} after 017, not by the migration/application account, so this must run as a
 * non-superuser {@code AUDIT_APP_ROLE}.
 *
 * <p>Proves #757's audit requirement (review of #763: an own event for the model that stops being
 * active) against a real database, not only against the Java enum: that {@code
 * chk_audit_log_event_type} actually accepts {@code LLM_MODEL_DEACTIVATED} after 061 runs, and that
 * every value accepted before it still is.
 */
class Migration061WidenAuditEventTypeLlmModelDeactivatedTest extends AbstractMigrationTest {

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
        appConnection,
        "db/changelog/changes/035-widen-audit-event-type-library-source-updated.yaml");
    applyChangelog(
        appConnection, "db/changelog/changes/040-widen-audit-event-type-space-archived.yaml");
    applyChangelog(
        appConnection,
        "db/changelog/changes/042-widen-audit-event-type-branding-settings-changed.yaml");
    applyChangelog(
        appConnection,
        "db/changelog/changes/053-widen-audit-event-type-library-detached-from-space.yaml");
    applyChangelog(
        appConnection, "db/changelog/changes/059-widen-audit-event-type-llm-model-changes.yaml");
    applyChangelog(
        appConnection,
        "db/changelog/changes/061-widen-audit-event-type-llm-model-deactivated.yaml");
  }

  @AfterEach
  void tearDown() throws SQLException {
    appConnection.close();
    bootstrapConnection.close();
    dropCurrentDatabaseNow();
    try (Connection admin = adminConnection();
        Statement statement = admin.createStatement()) {
      statement.execute("DROP ROLE IF EXISTS " + AUDIT_APP_ROLE);
      statement.execute("DROP ROLE IF EXISTS " + OWNER_ROLE);
    }
  }

  private void createNonSuperuserApplicationRole() throws SQLException {
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
  void llmModelDeactivatedIsAcceptedAfterTheWideningMigration() throws Exception {
    UUID eventId = insertEntry("LLM_MODEL_DEACTIVATED");
    assertThat(eventExists(eventId)).isTrue();
  }

  @Test
  void everyPreExistingEventTypeIsStillAcceptedAfterTheWideningMigration() throws Exception {
    // A widen must never accidentally narrow - spot-checked here for one representative value
    // per pre-061 category plus LLM_MODEL_ACTIVATED specifically: that is exactly what 059 added,
    // and therefore exactly what a value list re-derived from an older state would have dropped.
    for (String eventType :
        new String[] {
          "ASSET_GRANT_GRANTED",
          "SPACE_CREATED",
          "LIBRARY_CREATED",
          "LIBRARY_SOURCE_UPDATED",
          "SPACE_ARCHIVED",
          "LIBRARY_SHARED_TO_SPACE",
          "LIBRARY_DETACHED_FROM_SPACE",
          "BRANDING_SETTINGS_CHANGED",
          "SYSTEM_ADMIN_ROLE_GRANTED",
          "AUDITOR_ROLE_GRANTED",
          "DIRECTORY_SYNC_RUN_COMPLETED",
          "GOVERNANCE_SETTINGS_CHANGED",
          "CONNECTOR_LIBRARY_SHARE_LIMIT_CHANGED",
          "LLM_MODEL_CREATED",
          "LLM_MODEL_CHANGED",
          "LLM_MODEL_DELETED",
          "LLM_MODEL_ACTIVATED",
          "AUDIT_LOG_ACCESSED"
        }) {
      UUID eventId = insertEntry(eventType);
      assertThat(eventExists(eventId)).as("event_type %s still accepted", eventType).isTrue();
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
              + "', 'SYSTEM_SETTING', 'pseud-subject-1', 'SUCCESS')");
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
