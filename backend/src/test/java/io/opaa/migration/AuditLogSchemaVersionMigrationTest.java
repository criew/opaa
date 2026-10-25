package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code schema_version} on an existing installation: an entry written before keeps {@code NULL},
 * the table and its partitions stay with {@code opaa_audit_owner} (ADR-0015), the migration account
 * holds no membership of that role afterwards, and the application account's table grants cover the
 * new column.
 */
class AuditLogSchemaVersionMigrationTest extends AbstractMigrationTest {

  private static final String SCRIPT =
      "db/migration/identity/V2026.10.25.1__audit_log_schema_version.sql";
  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";
  private static final String APP_ROLE = "audit_schema_version_test_role";
  private static final String APP_ROLE_PASSWORD = "audit_schema_version_test_password";

  private Connection connection;

  @Override
  protected List<String> baseFixtureMigrations() {
    return Migrations.except(SCRIPT);
  }

  @BeforeEach
  void setUp() throws SQLException {
    connection = connect();
    connection.setAutoCommit(true);
    dropRolesIfExist(connection, APP_ROLE);
    execute(connection, "CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_ROLE_PASSWORD + "'");
    execute(connection, "GRANT INSERT, SELECT ON audit_log TO " + APP_ROLE);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
    dropCurrentDatabaseNow();
    try (Connection admin = adminConnection();
        Statement statement = admin.createStatement()) {
      statement.execute("DROP ROLE IF EXISTS " + APP_ROLE);
    }
  }

  @Test
  void anEntryWrittenBeforeKeepsNoSchemaVersion() throws SQLException {
    UUID before = insert(connection, null);

    applyMigrations(connection, SCRIPT);

    assertThat(single("SELECT schema_version FROM audit_log WHERE event_id = '" + before + "'"))
        .isNull();
    assertThat(
            single(
                "SELECT data_type || ',' || is_nullable || ',' || coalesce(column_default, '-')"
                    + " FROM information_schema.columns WHERE table_schema = current_schema()"
                    + " AND table_name = 'audit_log' AND column_name = 'schema_version'"))
        .isEqualTo("smallint,YES,-");
  }

  @Test
  void theLogAndItsPartitionsStayWithTheAuditOwner() throws SQLException {
    applyMigrations(connection, SCRIPT);

    assertThat(
            strings(
                "SELECT DISTINCT pg_get_userbyid(c.relowner) FROM pg_class c"
                    + " WHERE c.oid = 'audit_log'::regclass OR c.oid IN"
                    + " (SELECT inhrelid FROM pg_inherits WHERE inhparent = 'audit_log'::regclass)"))
        .containsExactly("opaa_audit_owner");
    assertThat(
            single(
                "SELECT count(*)::text FROM pg_auth_members WHERE roleid ="
                    + " 'opaa_audit_owner'::regrole AND member = current_user::regrole"))
        .isEqualTo("0");
  }

  @Test
  void theApplicationAccountWritesAndReadsTheVersion() throws SQLException {
    applyMigrations(connection, SCRIPT);

    try (Connection app = connect(APP_ROLE, APP_ROLE_PASSWORD)) {
      UUID written = insert(app, (short) 1);
      assertThat(
              single(
                  app,
                  "SELECT schema_version::text FROM audit_log WHERE event_id = '" + written + "'"))
          .isEqualTo("1");
    }
    assertThat(
            single(
                "SELECT count(*)::text FROM pg_auth_members WHERE roleid ="
                    + " 'opaa_audit_owner'::regrole AND member = '"
                    + APP_ROLE
                    + "'::regrole"))
        .isEqualTo("0");
  }

  private static UUID insert(Connection target, Short schemaVersion) throws SQLException {
    UUID eventId = UUID.randomUUID();
    execute(
        target,
        "INSERT INTO audit_log (event_id, recorded_at, organization_id, actor_kind, actor_ref,"
            + " event_type, object_type, object_id, outcome"
            + (schemaVersion == null ? "" : ", schema_version")
            + ") VALUES ('"
            + eventId
            + "', now(), '"
            + SEEDED_ORGANIZATION_ID
            + "', 'USER', 'pseud-actor-1', 'SPACE_CREATED', 'SPACE', 'space-1', 'SUCCESS'"
            + (schemaVersion == null ? "" : ", " + schemaVersion)
            + ")");
    return eventId;
  }

  private String single(String sql) throws SQLException {
    return single(connection, sql);
  }

  private static String single(Connection target, String sql) throws SQLException {
    try (Statement statement = target.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      assertThat(result.next()).as(sql).isTrue();
      return result.getString(1);
    }
  }

  private List<String> strings(String sql) throws SQLException {
    List<String> values = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      while (result.next()) {
        values.add(result.getString(1));
      }
    }
    return values;
  }

  private static void execute(Connection target, String sql) throws SQLException {
    try (Statement statement = target.createStatement()) {
      statement.execute(sql);
    }
  }
}
