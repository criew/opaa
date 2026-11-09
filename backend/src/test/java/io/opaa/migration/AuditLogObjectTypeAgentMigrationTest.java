package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The object types {@code AGENT} and {@code DOCUMENT_TEMPLATE} on an existing installation: entries
 * written before stay, both types are accepted, an unknown one is still refused, and the migration
 * account holds no membership of {@code opaa_audit_owner} afterwards (ADR-0015).
 */
class AuditLogObjectTypeAgentMigrationTest extends AbstractBaselineTest {

  private static final String SCRIPT =
      "db/migration/identity/V2026.11.10.21__audit_log_object_type_agent.sql";

  @Override
  protected List<String> baseFixtureMigrations() {
    return Migrations.except(SCRIPT);
  }

  @Test
  void anExistingEntryStaysAndAgentsAndTemplatesAreObjectsFromNowOn() throws SQLException {
    UUID before = insert("SPACE");
    assertRejected(insertSql(UUID.randomUUID(), "AGENT"), "chk_audit_log_object_type");

    applyMigrations(connection, SCRIPT);

    assertThat(countWhere("audit_log", "event_id = '" + before + "'")).isEqualTo(1);
    for (String type : List.of("AGENT", "DOCUMENT_TEMPLATE")) {
      UUID written = insert(type);
      assertThat(stringOf("SELECT object_type FROM audit_log WHERE event_id = '" + written + "'"))
          .isEqualTo(type);
    }
    assertRejected(insertSql(UUID.randomUUID(), "UNKNOWN_OBJECT"), "chk_audit_log_object_type");
  }

  @Test
  void theMigrationAccountGivesTheAuditOwnerRoleBack() throws SQLException {
    applyMigrations(connection, SCRIPT);

    assertThat(
            longOf(
                "SELECT count(*) FROM pg_auth_members WHERE roleid = 'opaa_audit_owner'::regrole"
                    + " AND member = current_user::regrole"))
        .isZero();
    assertThat(
            stringOf("SELECT pg_get_userbyid(relowner) FROM pg_class WHERE relname = 'audit_log'"))
        .isEqualTo("opaa_audit_owner");
  }

  private UUID insert(String objectType) throws SQLException {
    UUID eventId = UUID.randomUUID();
    execute(insertSql(eventId, objectType));
    return eventId;
  }

  private static String insertSql(UUID eventId, String objectType) {
    return "INSERT INTO audit_log (event_id, recorded_at, organization_id, actor_kind, actor_ref,"
        + " event_type, object_type, object_id, outcome) VALUES ('"
        + eventId
        + "', now(), '"
        + SEEDED_ORGANIZATION_ID
        + "', 'USER', 'pseud-actor-1', 'AGENT_CREATED', '"
        + objectType
        + "', 'object-1', 'SUCCESS')";
  }
}
