package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
 * The connection log applied to an existing installation (delta test) and its privilege model
 * (ADR-0015, ADR-0041 Entscheidung 7): an account with exactly the application's grants appends and
 * reads but can neither update, delete nor truncate, and the SECURITY DEFINER function drops only
 * expired monthly partitions. The restricted role is provisioned per test method, as in {@link
 * DiagnosticContextPrivilegeModelTest}; {@code opaa_audit_owner} comes from the fixture and stays.
 */
class ConnectionLogPrivilegeModelTest extends AbstractBaselineTest {

  private static final String FILE = "db/changelog/connections/2026-10-04-connection-log.yaml";
  private static final String OWNER_ROLE = "opaa_audit_owner";
  private static final String APP_ROLE = "connection_log_test_role";
  private static final String APP_ROLE_PASSWORD = "connection_log_test_password";
  private static final String DELETION = "opaa_connection_log_delete_expired_partitions";

  private Connection appConnection;

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  @BeforeEach
  void applyTheChangelogAndProvisionTheApplicationRole() throws Exception {
    applyChangelog(connection, FILE);
    connection.setAutoCommit(true);
    dropRolesIfExist(connection, APP_ROLE);
    execute("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_ROLE_PASSWORD + "'");
    execute("GRANT INSERT, SELECT ON connection_log TO " + APP_ROLE);
    execute("GRANT SELECT ON connection_log_retention_settings TO " + APP_ROLE);
    execute(
        "GRANT UPDATE (retention_months, updated_at) ON connection_log_retention_settings TO "
            + APP_ROLE);
    execute("GRANT EXECUTE ON FUNCTION " + DELETION + "() TO " + APP_ROLE);
    appConnection = connect(APP_ROLE, APP_ROLE_PASSWORD);
  }

  @AfterEach
  void dropTheApplicationRole() throws SQLException {
    appConnection.close();
    connection.close();
    dropCurrentDatabaseNow();
    try (Connection admin = adminConnection();
        Statement statement = admin.createStatement()) {
      statement.execute("DROP ROLE IF EXISTS " + APP_ROLE);
    }
  }

  /** Who, which profile, which event, when and why - no token, no account name, no library. */
  @Test
  void theLogCarriesExactlyItsSpecifiedColumnsAndIsPartitionedAhead() throws SQLException {
    List<String> columns = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT column_name FROM information_schema.columns WHERE table_schema ="
                    + " current_schema() AND table_name = 'connection_log' ORDER BY"
                    + " ordinal_position")) {
      while (rs.next()) {
        columns.add(rs.getString(1));
      }
    }

    assertThat(columns)
        .containsExactly(
            "event_id",
            "organization_id",
            "recorded_at",
            "event_type",
            "actor_ref",
            "person_ref",
            "profile_id",
            "profile_name",
            "cause");
    assertThat(stringOf("SELECT relkind FROM pg_class WHERE relname = 'connection_log'"))
        .isEqualTo("p");
    assertThat(
            longOf("SELECT count(*) FROM pg_inherits WHERE inhparent = 'connection_log'::regclass"))
        .isGreaterThan(100);
    assertThat(
            booleanOf(
                "SELECT EXISTS (SELECT 1 FROM pg_inherits WHERE inhparent ="
                    + " 'connection_log'::regclass AND inhrelid::regclass::text = 'connection_log_'"
                    + " || to_char(now() + interval '10 years', 'YYYY_MM'))"))
        .as("partitions reach years ahead")
        .isTrue();
  }

  @Test
  void rejectsAnUnknownEventTypeOrCause() {
    assertRejected(insertSql("'GRANTED'", "'SELF'", "now()"), "chk_connection_log_event_type");
    assertRejected(insertSql("'DISCONNECTED'", "'BORED'", "now()"), "chk_connection_log_cause");
    for (String cause :
        List.of(
            "SELF",
            "EMERGENCY",
            "ADDRESS_CHANGED",
            "REGISTRATION_CHANGED",
            "ACCOUNT_DEACTIVATED",
            "PROFILE_DELETED",
            "PROVIDER_REJECTED",
            "SECRET_EXPIRED")) {
      assertThatCode(() -> execute(insertSql("'DELETED'", "'" + cause + "'", "now()")))
          .doesNotThrowAnyException();
    }
  }

  /** A start never names a cause, every other event does: no end without a reason. */
  @Test
  void couplesTheCauseToTheEvent() {
    assertRejected(insertSql("'DISCONNECTED'", "NULL", "now()"), "chk_connection_log_cause_of_end");
    assertRejected(insertSql("'EXPIRED'", "NULL", "now()"), "chk_connection_log_cause_of_end");
    assertRejected(insertSql("'CONNECTED'", "'SELF'", "now()"), "chk_connection_log_cause_of_end");
    assertRejected(
        insertSql("'RECONNECTED'", "'EMERGENCY'", "now()"), "chk_connection_log_cause_of_end");
    assertThatCode(() -> execute(insertSql("'CONNECTED'", "NULL", "now()")))
        .doesNotThrowAnyException();
    assertThatCode(() -> execute(insertSql("'RECONNECTED'", "NULL", "now()")))
        .doesNotThrowAnyException();
    assertThatCode(() -> execute(insertSql("'EXPIRED'", "'SECRET_EXPIRED'", "now()")))
        .doesNotThrowAnyException();
  }

  /**
   * The changelog takes the owner role with SET only for its own statements and gives it back; the
   * ADMIN OPTION residual of ADR-0015 is not a SET membership and stays out of this check.
   */
  @Test
  void theMigrationAccountGivesTheSetMembershipBack() throws SQLException {
    assertThat(
            longOf(
                "SELECT count(*) FROM pg_auth_members WHERE roleid = '"
                    + OWNER_ROLE
                    + "'::regrole AND member = current_user::regrole AND set_option"))
        .isZero();
  }

  @Test
  void movesOwnershipToTheAuditOwnerRole() throws SQLException {
    assertThat(ownerOf("connection_log")).isEqualTo(OWNER_ROLE);
    assertThat(ownerOf("connection_log_retention_settings")).isEqualTo(OWNER_ROLE);
    assertThat(
            stringOf(
                "SELECT pg_get_userbyid(proowner) FROM pg_proc WHERE proname = '" + DELETION + "'"))
        .isEqualTo(OWNER_ROLE);
    assertThat(booleanOf("SELECT prosecdef FROM pg_proc WHERE proname = '" + DELETION + "'"))
        .isTrue();
  }

  /** The grants the role above copies must be the grants the changelog hands out. */
  @Test
  void grantsTheApplicationAccountExactlyInsertAndSelect() throws SQLException {
    String migrationAccount = stringOf("SELECT current_user");

    assertThat(tablePrivileges("connection_log", migrationAccount))
        .containsExactlyInAnyOrder("INSERT", "SELECT");
    assertThat(tablePrivileges("connection_log_retention_settings", migrationAccount))
        .containsExactly("SELECT");
    assertThat(updatableColumns("connection_log_retention_settings", migrationAccount))
        .containsExactlyInAnyOrder("retention_months", "updated_at");
    assertThat(updatableColumns("connection_log", migrationAccount)).isEmpty();
    assertThat(
            booleanOf("SELECT has_function_privilege('public', '" + DELETION + "()', 'EXECUTE')"))
        .isFalse();
  }

  @Test
  void theApplicationAccountAppendsAndReadsButNeverChangesAnEntry() throws Exception {
    executeAs(appConnection, insertSql("'CONNECTED'", "NULL", "now()"));
    String partition = stringOf("SELECT tableoid::regclass::text FROM connection_log LIMIT 1");

    assertThat(countAs(appConnection)).isEqualTo(1);
    assertDenied("UPDATE connection_log SET profile_name = 'x'", "permission denied");
    assertDenied("DELETE FROM connection_log", "permission denied");
    assertDenied("TRUNCATE TABLE connection_log", "permission denied");
    assertDenied("UPDATE " + partition + " SET profile_name = 'x'", "permission denied");
    assertDenied("DELETE FROM " + partition, "permission denied");
    assertDenied("TRUNCATE TABLE " + partition, "permission denied");
    assertDenied("ALTER TABLE connection_log DETACH PARTITION " + partition, "must be owner");
    assertDenied(
        "ALTER TABLE connection_log DROP CONSTRAINT chk_connection_log_event_type",
        "must be owner");
    assertDenied(
        "UPDATE connection_log_retention_settings SET last_cutoff = now()", "permission denied");
    assertThatThrownBy(() -> executeAs(appConnection, "SET ROLE " + OWNER_ROLE))
        .isInstanceOf(SQLException.class);
    assertThat(countAs(connection)).isEqualTo(1);
  }

  @Test
  void seedsTwelveMonthsAndBoundsTheRetentionToSixThroughTwentyFour() throws Exception {
    assertThat(longOf("SELECT retention_months FROM connection_log_retention_settings"))
        .isEqualTo(12);

    for (int outside : new int[] {5, 25, 0}) {
      assertThatThrownBy(() -> executeAs(appConnection, setRetention(outside)))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("chk_connection_log_retention_months");
    }
    executeAs(appConnection, setRetention(6));
    executeAs(appConnection, setRetention(24));
    assertThat(longOf("SELECT retention_months FROM connection_log_retention_settings"))
        .isEqualTo(24);
    assertRejected(
        "INSERT INTO connection_log_retention_settings (id, retention_months, updated_at)"
            + " VALUES (2, 12, now())",
        "chk_connection_log_retention_singleton");
  }

  /**
   * A partition past the period goes with its rows, one inside stays; the run is the application
   * account's, through the function, and a second run finds nothing left.
   */
  @Test
  void dropsAnExpiredPartitionAndKeepsOneInsideThePeriod() throws Exception {
    createOwnedPartitionMonthsAgo(14);
    createOwnedPartitionMonthsAgo(7);
    execute(insertSql("'CONNECTED'", "NULL", monthsAgo(14)));
    execute(insertSql("'DISCONNECTED'", "'SELF'", monthsAgo(7)));
    execute(
        "UPDATE connection_log_retention_settings SET retention_months = 12, last_cutoff ="
            + " date_trunc('month', now()) - interval '14 months'");

    assertThat(runDeletion()).containsExactly(partitionNameMonthsAgo(14));

    assertThat(countAs(connection)).isEqualTo(1);
    assertThat(stringOf("SELECT event_type FROM connection_log")).isEqualTo("DISCONNECTED");
    assertThat(
            booleanOf(
                "SELECT last_cutoff = date_trunc('month', now()) - interval '12 months' FROM"
                    + " connection_log_retention_settings"))
        .isTrue();
    assertThat(runDeletion()).isEmpty();
  }

  /** A run on a fresh installation has nothing due and must be a no-op, not an error. */
  @Test
  void aRunWithNothingDueDropsNothing() throws Exception {
    long partitions =
        longOf("SELECT count(*) FROM pg_inherits WHERE inhparent = 'connection_log'::regclass");

    assertThat(runDeletion()).isEmpty();

    assertThat(
            longOf("SELECT count(*) FROM pg_inherits WHERE inhparent = 'connection_log'::regclass"))
        .isEqualTo(partitions);
  }

  private String insertSql(String eventType, String cause, String recordedAt) {
    return "INSERT INTO connection_log (event_id, organization_id, recorded_at, event_type,"
        + " actor_ref, person_ref, profile_id, profile_name, cause) VALUES ('"
        + UUID.randomUUID()
        + "', '"
        + SEEDED_ORGANIZATION_ID
        + "', "
        + recordedAt
        + ", "
        + eventType
        + ", 'actor-pseudonym', 'person-pseudonym', '"
        + UUID.randomUUID()
        + "', 'Nextcloud Rathaus', "
        + cause
        + ")";
  }

  private static String monthsAgo(int months) {
    return "date_trunc('month', now()) - interval '" + months + " months' + interval '2 days'";
  }

  private static String setRetention(int months) {
    return "UPDATE connection_log_retention_settings SET retention_months = "
        + months
        + ", updated_at = now()";
  }

  private void createOwnedPartitionMonthsAgo(int months) throws SQLException {
    String name = partitionNameMonthsAgo(months);
    execute(
        "CREATE TABLE "
            + name
            + " PARTITION OF connection_log FOR VALUES FROM ((date_trunc('month', now()) -"
            + " interval '"
            + months
            + " months')::date) TO ((date_trunc('month', now()) - interval '"
            + (months - 1)
            + " months')::date)");
    execute("ALTER TABLE " + name + " OWNER TO " + OWNER_ROLE);
  }

  private String partitionNameMonthsAgo(int months) throws SQLException {
    return stringOf(
        "SELECT 'connection_log_' || to_char(date_trunc('month', now()) - interval '"
            + months
            + " months', 'YYYY_MM')");
  }

  private List<String> runDeletion() throws SQLException {
    List<String> dropped = new ArrayList<>();
    try (Statement statement = appConnection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT * FROM " + DELETION + "()")) {
      while (rs.next()) {
        dropped.add(rs.getString(1));
      }
    }
    return dropped;
  }

  private void assertDenied(String sql, String expected) {
    assertThatThrownBy(() -> executeAs(appConnection, sql))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining(expected);
  }

  private static void executeAs(Connection target, String sql) throws SQLException {
    try (Statement statement = target.createStatement()) {
      statement.execute(sql);
    }
  }

  private static long countAs(Connection target) throws SQLException {
    try (Statement statement = target.createStatement();
        ResultSet rs = statement.executeQuery("SELECT count(*) FROM connection_log")) {
      assertThat(rs.next()).isTrue();
      return rs.getLong(1);
    }
  }

  private String ownerOf(String table) throws SQLException {
    return stringOf(
        "SELECT tableowner FROM pg_tables WHERE schemaname = current_schema() AND tablename = '"
            + table
            + "'");
  }

  private List<String> tablePrivileges(String table, String grantee) throws SQLException {
    return strings(
        "SELECT DISTINCT privilege_type FROM information_schema.table_privileges WHERE"
            + " table_schema = current_schema() AND table_name = ? AND grantee = ?",
        table,
        grantee);
  }

  private List<String> updatableColumns(String table, String grantee) throws SQLException {
    return strings(
        "SELECT DISTINCT column_name FROM information_schema.column_privileges WHERE"
            + " table_schema = current_schema() AND table_name = ? AND grantee = ? AND"
            + " privilege_type = 'UPDATE'",
        table,
        grantee);
  }

  private List<String> strings(String sql, String first, String second) throws SQLException {
    List<String> values = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, first);
      statement.setString(2, second);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          values.add(rs.getString(1));
        }
      }
    }
    return values;
  }
}
