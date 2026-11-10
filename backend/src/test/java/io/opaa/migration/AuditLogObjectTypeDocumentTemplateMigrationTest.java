package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The object types {@code DOCUMENT_TEMPLATE} and {@code AGENT} on an existing installation: entries
 * written before stay, both new types are accepted, an unknown one is still refused, and the
 * migration account holds no membership of {@code opaa_audit_owner} afterwards (ADR-0015).
 */
class AuditLogObjectTypeDocumentTemplateMigrationTest extends AbstractBaselineTest {

  private static final String SCRIPT =
      "db/migration/identity/V2026.11.10.11__audit_log_object_type_document_template.sql";

  @Override
  protected List<String> baseFixtureMigrations() {
    return Migrations.except(SCRIPT);
  }

  @Test
  void anExistingEntryStaysAndTemplatesAndAgentsAreObjectsFromNowOn() throws SQLException {
    UUID before = insert("SERVICE_ACCOUNT");

    applyMigrations(connection, SCRIPT);

    assertThat(countWhere("audit_log", "event_id = '" + before + "'")).isEqualTo(1);
    for (String objectType : List.of("DOCUMENT_TEMPLATE", "AGENT")) {
      UUID written = insert(objectType);
      assertThat(stringOf("SELECT object_type FROM audit_log WHERE event_id = '" + written + "'"))
          .isEqualTo(objectType);
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
        + "', 'USER', 'pseud-actor-1', 'DOCUMENT_TEMPLATE_CREATED', '"
        + objectType
        + "', 'object-1', 'SUCCESS')";
  }
}
