package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta test for {@code changes/007-diagnostic-context-log.yaml} (#1052): the protocol of diagnoses
 * run in a foreign rights context. Two groups of assertions, matching the two things the
 * leitplanken make binding about it - that a Personenkontext without Begründung cannot be stored at
 * all, and that the entry is unveränderlich in the ADR-0015 sense, i.e. an account holding exactly
 * the application's own grants can insert and read but neither update, delete nor truncate, not
 * even through a named partition.
 *
 * <p>The restricted account is provisioned here rather than by re-running the changeset as a
 * non-superuser - same reasoning and same shape as {@link AuditPrivilegeModelTest}, see its
 * Javadoc. {@code opaa_audit_owner} is deliberately never dropped by this class: it is created by
 * the baseline, which this class's own fixture chain applies at template-build time (see {@link
 * AbstractMigrationTest}, "Important asymmetry").
 */
class Migration007DiagnosticContextLogTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/007-diagnostic-context-log.yaml";
  private static final UUID ORGANIZATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String OWNER_ROLE = "opaa_audit_owner";
  private static final String APP_ROLE = "diagnostic_context_log_test_role";
  private static final String APP_ROLE_PASSWORD = "diagnostic_context_log_test_password";

  private Connection connection;
  private Connection appConnection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    applyChangelog(connection, CHANGELOG_PATH);
    provisionApplicationRole();
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
  void createsThePartitionedProtocolTable() throws SQLException {
    assertThat(columnType("event_id")).isEqualTo("uuid");
    assertThat(columnType("recorded_at")).isEqualTo("timestamp with time zone");
    assertThat(columnType("actor_ref")).isEqualTo("character varying");
    assertThat(columnType("target_kind")).isEqualTo("character varying");
    assertThat(columnType("target_ref")).isEqualTo("character varying");
    assertThat(columnType("test_question")).isEqualTo("character varying");
    assertThat(columnType("hit_count")).isEqualTo("integer");
    assertThat(columnType("hit_refs")).isEqualTo("text");
    assertThat(columnType("permission_snapshot")).isEqualTo("text");
    assertThat(columnType("justification")).isEqualTo("character varying");
    assertThat(isPartitioned()).isTrue();
    assertThat(partitionCount()).isGreaterThan(100);
  }

  @Test
  void rejectsAPersonContextWithoutAJustification() {
    assertThatThrownBy(() -> insertEntry("USER", "pseudonym", null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_diagnostic_context_log_justification");

    assertThatThrownBy(() -> insertEntry("USER", "pseudonym", "   "))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_diagnostic_context_log_justification");
  }

  @Test
  void acceptsAProfileContextWithoutAJustification() {
    assertThatCode(() -> insertEntry("PERMISSION_PROFILE", "Sachbearbeitung Bauamt", null))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsAnUnknownTargetKind() {
    assertThatThrownBy(() -> insertEntry("EVERYONE", "x", "weil"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_diagnostic_context_log_target_kind");
  }

  @Test
  void movesOwnershipToTheRestrictedAuditOwnerRole() throws SQLException {
    assertThat(ownerOf("diagnostic_context_log")).isEqualTo(OWNER_ROLE);
    assertThat(ownerOf("diagnostic_context_retention_settings")).isEqualTo(OWNER_ROLE);
  }

  @Test
  void applicationAccountCanAppendAndReadButNeverChangeAnEntry() throws Exception {
    UUID eventId = insertEntryAs(appConnection, "PERMISSION_PROFILE", "Profil", null);

    assertThat(countAs(appConnection)).isEqualTo(1);
    assertThatThrownBy(
            () -> execute(appConnection, "UPDATE diagnostic_context_log SET hit_count = 99"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
    assertThatThrownBy(
            () ->
                execute(
                    appConnection,
                    "DELETE FROM diagnostic_context_log WHERE event_id = '" + eventId + "'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
    assertThatThrownBy(() -> execute(appConnection, "TRUNCATE TABLE diagnostic_context_log"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
  }

  /** Every partition carries its own ACL - a parent-only grant would not cover this (ADR-0015). */
  @Test
  void applicationAccountCannotChangeANamedPartitionEither() throws Exception {
    UUID eventId = insertEntryAs(appConnection, "PERMISSION_PROFILE", "Profil", null);
    String partition = partitionNameOf(eventId);

    assertThatThrownBy(() -> execute(appConnection, "UPDATE " + partition + " SET hit_count = 99"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
    assertThatThrownBy(() -> execute(appConnection, "TRUNCATE TABLE " + partition))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
  }

  @Test
  void seedsTwelveMonthsRetentionAndBoundsIt() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT retention_months FROM diagnostic_context_retention_settings WHERE id = 1")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt("retention_months")).isEqualTo(12);
    }

    assertThatThrownBy(
            () ->
                execute(
                    connection,
                    "UPDATE diagnostic_context_retention_settings SET retention_months = 600"
                        + " WHERE id = 1"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_diagnostic_context_retention_months");
  }

  @Test
  void providesTheOnlyDeletionPathAsASecurityDefinerFunction() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT prosecdef FROM pg_proc WHERE proname ="
                    + " 'opaa_diagnostic_context_delete_expired_partitions'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getBoolean("prosecdef")).isTrue();
    }
    // Nothing has expired in a freshly created horizon, so the call must be a safe no-op rather
    // than an error - the scheduler runs it monthly regardless of whether anything is due.
    try (Statement statement = appConnection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT * FROM opaa_diagnostic_context_delete_expired_partitions()")) {
      assertThat(rs.next()).isFalse();
    }
  }

  private void provisionApplicationRole() throws SQLException {
    dropRolesIfExist(connection, APP_ROLE);
    try (Statement statement = connection.createStatement()) {
      statement.execute("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_ROLE_PASSWORD + "'");
      statement.execute("GRANT INSERT, SELECT ON diagnostic_context_log TO " + APP_ROLE);
      statement.execute("GRANT SELECT ON diagnostic_context_retention_settings TO " + APP_ROLE);
      statement.execute(
          "GRANT UPDATE (retention_months, updated_at) ON diagnostic_context_retention_settings TO "
              + APP_ROLE);
      statement.execute(
          "GRANT EXECUTE ON FUNCTION opaa_diagnostic_context_delete_expired_partitions() TO "
              + APP_ROLE);
    }
  }

  private UUID insertEntry(String targetKind, String targetRef, String justification)
      throws SQLException {
    return insertEntryAs(connection, targetKind, targetRef, justification);
  }

  private UUID insertEntryAs(
      Connection target, String targetKind, String targetRef, String justification)
      throws SQLException {
    UUID eventId = UUID.randomUUID();
    try (PreparedStatement statement =
        target.prepareStatement(
            "INSERT INTO diagnostic_context_log (event_id, recorded_at, organization_id,"
                + " actor_ref, target_kind, target_ref, test_question, hit_count, hit_refs,"
                + " permission_snapshot, justification)"
                + " VALUES (?, now(), ?, 'actor-pseudonym', ?, ?, 'Wo steht die Dienstanweisung?',"
                + " 2, 'a,b', 'libraries=[];lockedLibraries=[]', ?)")) {
      statement.setObject(1, eventId);
      statement.setObject(2, ORGANIZATION_ID);
      statement.setString(3, targetKind);
      statement.setString(4, targetRef);
      statement.setString(5, justification);
      statement.executeUpdate();
    }
    return eventId;
  }

  private int countAs(Connection target) throws SQLException {
    try (Statement statement = target.createStatement();
        ResultSet rs = statement.executeQuery("SELECT count(*) FROM diagnostic_context_log")) {
      assertThat(rs.next()).isTrue();
      return rs.getInt(1);
    }
  }

  private String partitionNameOf(UUID eventId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT tableoid::regclass::text FROM diagnostic_context_log WHERE event_id = ?")) {
      statement.setObject(1, eventId);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getString(1);
      }
    }
  }

  private String ownerOf(String tableName) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT tableowner FROM pg_tables WHERE schemaname = 'public' AND tablename = ?")) {
      statement.setString(1, tableName);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getString("tableowner");
      }
    }
  }

  private boolean isPartitioned() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT relkind FROM pg_class WHERE relname = 'diagnostic_context_log'")) {
      assertThat(rs.next()).isTrue();
      return "p".equals(rs.getString("relkind"));
    }
  }

  private int partitionCount() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT count(*) FROM pg_inherits WHERE inhparent ="
                    + " 'public.diagnostic_context_log'::regclass")) {
      assertThat(rs.next()).isTrue();
      return rs.getInt(1);
    }
  }

  private String columnType(String columnName) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT data_type FROM information_schema.columns WHERE table_schema = 'public'"
                + " AND table_name = 'diagnostic_context_log' AND column_name = ?")) {
      statement.setString(1, columnName);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).as("column %s must exist", columnName).isTrue();
        return rs.getString("data_type");
      }
    }
  }

  private void execute(Connection target, String sql) throws SQLException {
    try (Statement statement = target.createStatement()) {
      statement.execute(sql);
    }
  }
}
