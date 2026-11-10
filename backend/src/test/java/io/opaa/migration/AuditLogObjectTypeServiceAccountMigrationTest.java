package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The object type {@code SERVICE_ACCOUNT} on an existing installation: entries written before stay,
 * the new type is accepted, an unknown one is still refused, and the migration account holds no
 * membership of {@code opaa_audit_owner} afterwards (ADR-0015).
 */
class AuditLogObjectTypeServiceAccountMigrationTest extends AbstractBaselineTest {

  private static final String SCRIPT =
      "db/migration/identity/V2026.11.05.33__audit_log_object_type_service_account.sql";

  @Override
  protected List<String> baseFixtureMigrations() {
    // Later scripts that keep SERVICE_ACCOUNT in the list refuse to run before this one.
    return Migrations.before(SCRIPT);
  }

  @Test
  void anExistingEntryStaysAndTheServiceAccountIsAnObjectFromNowOn() throws SQLException {
    UUID before = insert("SPACE");
    assertRejected(insertSql(UUID.randomUUID(), "SERVICE_ACCOUNT"), "chk_audit_log_object_type");

    applyMigrations(connection, SCRIPT);

    assertThat(countWhere("audit_log", "event_id = '" + before + "'")).isEqualTo(1);
    UUID written = insert("SERVICE_ACCOUNT");
    assertThat(stringOf("SELECT object_type FROM audit_log WHERE event_id = '" + written + "'"))
        .isEqualTo("SERVICE_ACCOUNT");
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
        + "', 'USER', 'pseud-actor-1', 'SERVICE_ACCOUNT_CREATED', '"
        + objectType
        + "', 'object-1', 'SUCCESS')";
  }
}
