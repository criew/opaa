package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
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
 * The privilege model of the step log and the sandbox log of agent runs (ADR-0015, ADR-0070): both
 * belong to {@code opaa_audit_owner}, an account with exactly the application's grants appends and
 * reads but can neither change nor remove a row, and {@code opaa_agent_run_logs_maintain()} is the
 * only way anything goes - by dropping a whole day once it is past the retention period. The
 * restricted role is provisioned here with the grants the migration hands out, which {@link
 * #grantsTheApplicationAccountExactlyInsertAndSelect} pins against the migration's own ACL.
 */
class AgentRunLogPrivilegeModelTest extends AbstractMigrationTest {

  private static final UUID ORGANIZATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String OWNER_ROLE = "opaa_audit_owner";
  private static final String APP_ROLE = "agent_run_log_test_role";
  private static final String APP_ROLE_PASSWORD = "agent_run_log_test_password";

  private Connection connection;
  private Connection appConnection;

  @Override
  protected List<String> baseFixtureMigrations() {
    return Migrations.all();
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    dropRolesIfExist(connection, APP_ROLE);
    execute(connection, "CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_ROLE_PASSWORD + "'");
    execute(connection, "GRANT INSERT, SELECT ON agent_step_log, agent_sandbox_log TO " + APP_ROLE);
    execute(connection, "GRANT SELECT ON agent_run_log_retention_settings TO " + APP_ROLE);
    execute(
        connection,
        "GRANT UPDATE (step_log_retention_days, sandbox_log_retention_days, updated_at) ON"
            + " agent_run_log_retention_settings TO "
            + APP_ROLE);
    execute(connection, "GRANT EXECUTE ON FUNCTION opaa_agent_run_logs_maintain() TO " + APP_ROLE);
    appConnection = connect(APP_ROLE, APP_ROLE_PASSWORD);
  }

  @AfterEach
  void tearDown() throws SQLException {
    appConnection.close();
    connection.close();
    dropCurrentDatabaseNow();
    try (Connection admin = adminConnection();
        Statement statement = admin.createStatement()) {
      statement.execute("DROP ROLE IF EXISTS " + APP_ROLE);
    }
  }

  @Test
  void bothLogsBelongToTheAuditOwnerAndAreCutIntoDays() throws SQLException {
    assertThat(ownerOf("agent_step_log")).isEqualTo(OWNER_ROLE);
    assertThat(ownerOf("agent_sandbox_log")).isEqualTo(OWNER_ROLE);
    assertThat(ownerOf("agent_run_log_retention_settings")).isEqualTo(OWNER_ROLE);
    assertThat(partitionsOf("agent_step_log")).hasSizeGreaterThanOrEqualTo(32);
    assertThat(partitionsOf("agent_sandbox_log")).hasSizeGreaterThanOrEqualTo(32);
    assertThat(partitionsOf("agent_step_log"))
        .allMatch(name -> name.matches("agent_step_log_\\d{8}"));
  }

  @Test
  void neitherLogHasAColumnForAnAccountOrAText() throws SQLException {
    assertThat(columnsOf("agent_step_log"))
        .containsExactlyInAnyOrder(
            "run_id",
            "step_index",
            "recorded_at",
            "organization_id",
            "tool",
            "status",
            "hit_count",
            "duration_ms",
            "abort_reason");
    assertThat(columnsOf("agent_sandbox_log"))
        .containsExactlyInAnyOrder(
            "run_id",
            "session_ref",
            "recorded_at",
            "organization_id",
            "tool",
            "status",
            "script_runs",
            "duration_ms",
            "file_count",
            "file_bytes",
            "abort_reason");
  }

  @Test
  void grantsTheApplicationAccountExactlyInsertAndSelect() throws SQLException {
    String migrationAccount = migrationAccount();
    assertThat(tablePrivilegesOf("agent_step_log", migrationAccount))
        .containsExactlyInAnyOrder("INSERT", "SELECT");
    assertThat(tablePrivilegesOf("agent_sandbox_log", migrationAccount))
        .containsExactlyInAnyOrder("INSERT", "SELECT");
    assertThat(tablePrivilegesOf("agent_run_log_retention_settings", migrationAccount))
        .containsExactly("SELECT");
  }

  @Test
  void theApplicationAccountAppendsAndReadsButNeverRemovesAStep() throws Exception {
    UUID runId = UUID.randomUUID();
    execute(
        appConnection,
        "INSERT INTO agent_step_log (run_id, step_index, organization_id, tool, status,"
            + " duration_ms) VALUES ('"
            + runId
            + "', 0, '"
            + ORGANIZATION_ID
            + "', 'search_knowledge', 'DONE', 5)");

    assertThat(count(appConnection, "agent_step_log", runId)).isOne();
    for (String change :
        List.of(
            "UPDATE agent_step_log SET status = 'X'",
            "DELETE FROM agent_step_log WHERE run_id = '" + runId + "'",
            "TRUNCATE TABLE agent_step_log",
            "DELETE FROM " + partitionsOf("agent_step_log").getLast(),
            "DROP TABLE " + partitionsOf("agent_step_log").getLast())) {
      assertThatThrownBy(() -> execute(appConnection, change))
          .as(change)
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  void theSandboxLogCannotBeKeptLongerThanTheStepLog() {
    assertThatThrownBy(
            () ->
                execute(
                    appConnection,
                    "UPDATE agent_run_log_retention_settings SET step_log_retention_days = 5,"
                        + " sandbox_log_retention_days = 6"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_agent_sandbox_log_retention_days");
    assertThatThrownBy(
            () ->
                execute(
                    appConnection,
                    "UPDATE agent_run_log_retention_settings SET step_log_retention_days = 31"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_agent_step_log_retention_days");
  }

  @Test
  void theMaintenanceDropsADayPastTheRetentionWithItsRows() throws Exception {
    String expired = "agent_step_log_" + day(-10);
    execute(
        connection,
        "CREATE TABLE "
            + expired
            + " PARTITION OF agent_step_log FOR VALUES FROM ((current_date - 10)::timestamp AT"
            + " TIME ZONE 'UTC') TO ((current_date - 9)::timestamp AT TIME ZONE 'UTC')");
    execute(connection, "ALTER TABLE " + expired + " OWNER TO " + OWNER_ROLE);
    UUID runId = UUID.randomUUID();
    execute(
        connection,
        "INSERT INTO agent_step_log (run_id, step_index, recorded_at, organization_id, tool,"
            + " status, duration_ms) VALUES ('"
            + runId
            + "', 0, (current_date - 10)::timestamp AT TIME ZONE 'UTC' + interval '1 hour', '"
            + ORGANIZATION_ID
            + "', 'search_knowledge', 'DONE', 5)");

    List<String> dropped = new ArrayList<>();
    try (Statement statement = appConnection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT * FROM opaa_agent_run_logs_maintain()")) {
      while (rs.next()) {
        dropped.add(rs.getString(1));
      }
    }

    assertThat(dropped).containsExactly(expired);
    assertThat(count(connection, "agent_step_log", runId)).isZero();
    assertThat(partitionsOf("agent_step_log")).contains("agent_step_log_" + day(30));
  }

  private String day(int offset) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT to_char((now() AT TIME ZONE 'UTC')::date + " + offset + ", 'YYYYMMDD')")) {
      rs.next();
      return rs.getString(1);
    }
  }

  private List<String> partitionsOf(String table) throws SQLException {
    List<String> names = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT inhrelid::regclass::text FROM pg_inherits WHERE inhparent = ?::regclass"
                + " ORDER BY 1")) {
      statement.setString(1, table);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          names.add(rs.getString(1));
        }
      }
    }
    return names;
  }

  private List<String> columnsOf(String table) throws SQLException {
    List<String> columns = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT column_name FROM information_schema.columns WHERE table_schema ="
                + " current_schema() AND table_name = ?")) {
      statement.setString(1, table);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          columns.add(rs.getString(1));
        }
      }
    }
    return columns;
  }

  private long count(Connection target, String table, UUID runId) throws SQLException {
    try (PreparedStatement statement =
        target.prepareStatement("SELECT count(*) FROM " + table + " WHERE run_id = ?")) {
      statement.setObject(1, runId);
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private String migrationAccount() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT current_user")) {
      rs.next();
      return rs.getString(1);
    }
  }

  private List<String> tablePrivilegesOf(String tableName, String grantee) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT DISTINCT privilege_type FROM information_schema.table_privileges WHERE"
                + " table_schema = current_schema() AND table_name = ? AND grantee = ?")) {
      statement.setString(1, tableName);
      statement.setString(2, grantee);
      List<String> privileges = new ArrayList<>();
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          privileges.add(rs.getString(1));
        }
      }
      return privileges;
    }
  }

  private String ownerOf(String tableName) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT tableowner FROM pg_tables WHERE schemaname = current_schema() AND tablename = ?")) {
      statement.setString(1, tableName);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getString("tableowner");
      }
    }
  }

  private static void execute(Connection target, String sql) throws SQLException {
    try (Statement statement = target.createStatement()) {
      statement.execute(sql);
    }
  }
}
