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
 * Delta test for {@code changes/007-diagnostic-context-log.yaml} (#1052): the protocol of diagnoses
 * run in a foreign rights context. Two groups of assertions, matching the two things the
 * leitplanken make binding about it - that a Personenkontext without Begründung cannot be stored at
 * all, and that the entry is unveränderlich in the ADR-0015 sense, i.e. an account holding exactly
 * the application's own grants can insert and read but neither update, delete nor truncate, not
 * even through a named partition.
 *
 * <p>The restricted account is provisioned here rather than by re-running the changeset as a
 * non-superuser - same reasoning and same shape as {@link AuditPrivilegeModelTest}, see its
 * Javadoc: Liquibase runs as an account that must be able to create the objects in the first place,
 * so the account the application later uses cannot be the account that applied the changeset. That
 * hand-provisioned role is only as truthful as the grants it copies, which is why {@link
 * #grantsTheApplicationAccountExactlyInsertAndSelect} asserts the ACL the changeset itself produced
 * - without it, a changeset handing out {@code UPDATE} would leave every "permission denied"
 * assertion below green. {@code opaa_audit_owner} is deliberately never dropped by this class: it
 * is created by the baseline, which this class's own fixture chain applies at template-build time
 * (see {@link AbstractMigrationTest}, "Important asymmetry").
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

  /**
   * A named partition is reached through its own, empty ACL rather than through the parent's grant
   * (ADR-0015; see {@link #grantsNothingOnAPartitionItself}), so naming one directly is no way
   * around the restriction either.
   */
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

  /**
   * The privileges {@link #provisionApplicationRole} hands the test role must be the privileges the
   * changeset hands the real application account - otherwise this class measures its own fixture.
   */
  @Test
  void grantsTheApplicationAccountExactlyInsertAndSelect() throws SQLException {
    assertThat(tablePrivilegesOf("diagnostic_context_log", changesetAccount()))
        .containsExactlyInAnyOrder("INSERT", "SELECT");
    assertThat(tablePrivilegesOf("diagnostic_context_retention_settings", changesetAccount()))
        .containsExactly("SELECT");
  }

  /** A partition carries no ACL of its own - nothing is granted on it, by anyone. */
  @Test
  void grantsNothingOnAPartitionItself() throws Exception {
    UUID eventId = insertEntryAs(appConnection, "PERMISSION_PROFILE", "Profil", null);
    String partition = partitionNameOf(eventId);

    assertThat(tablePrivilegesOf(partition.replace("public.", ""), changesetAccount())).isEmpty();
  }

  /**
   * The write bar on the retention setting is a column grant, so it appears in {@code
   * column_privileges} and not in {@code table_privileges} - the assertion above cannot see it.
   * Without this one, a grant widened to {@code last_cutoff} or {@code last_run_month} would go
   * unnoticed, and those two are the deletion function's own state: an account able to move them
   * could stall the deletion Leitplanke (i) requires.
   */
  @Test
  void grantsTheApplicationAccountUpdateOnExactlyTheTwoConfigurableColumns() throws SQLException {
    assertThat(updatableColumnsOf("diagnostic_context_retention_settings", changesetAccount()))
        .containsExactlyInAnyOrder("retention_months", "updated_at");
    assertThat(updatableColumnsOf("diagnostic_context_log", changesetAccount())).isEmpty();
  }

  /**
   * The account may not repair its own restriction. A {@code GRANT} by a grantor holding no
   * grantable privilege is a Postgres quirk - it raises only a WARNING the driver does not surface
   * - so this asserts the effect afterwards rather than the statement throwing, exactly as {@code
   * AuditPrivilegeModelTest} does.
   */
  @Test
  void applicationAccountCanNeitherGrantItselfMoreNorBecomeTheOwner() throws Exception {
    UUID eventId = insertEntryAs(appConnection, "PERMISSION_PROFILE", "Profil", null);
    try (Statement statement = appConnection.createStatement()) {
      statement.execute("GRANT ALL ON TABLE diagnostic_context_log TO " + APP_ROLE);
    } catch (SQLException expectedOrIgnored) {
      // Either outcome is acceptable - see this method's own Javadoc.
    }

    assertThatThrownBy(
            () ->
                execute(
                    appConnection,
                    "DELETE FROM diagnostic_context_log WHERE event_id = '" + eventId + "'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
    assertThatThrownBy(() -> execute(appConnection, "SET ROLE " + OWNER_ROLE))
        .isInstanceOf(SQLException.class);
  }

  /**
   * Nor may it weaken the table itself - the Begr\u00fcndungspflicht is a constraint, not a habit.
   */
  @Test
  void applicationAccountCannotDropAConstraintOrDetachAPartition() throws Exception {
    UUID eventId = insertEntryAs(appConnection, "PERMISSION_PROFILE", "Profil", null);
    String partition = partitionNameOf(eventId);

    assertThatThrownBy(
            () ->
                execute(
                    appConnection,
                    "ALTER TABLE diagnostic_context_log DROP CONSTRAINT"
                        + " chk_diagnostic_context_log_justification"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("must be owner");
    assertThatThrownBy(
            () ->
                execute(
                    appConnection,
                    "ALTER TABLE diagnostic_context_log DETACH PARTITION " + partition))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("must be owner");
  }

  /**
   * Leitplanke (i) asks for a deletion that is "automatisch und nachweisbar" - so this drops a real
   * partition with a real row in it, rather than asserting that a run with nothing due removes
   * nothing.
   */
  @Test
  void dropsAnExpiredPartitionWithItsRows() throws Exception {
    createOwnedPartitionMonthsAgo(6);
    insertEntryMonthsAgo(6);
    assertThat(countAs(connection)).isEqualTo(1);
    // retention 1 month, and the last run was a month ago: the forward cap allows exactly one
    // month of progress, from the sixth-last month to the fifth-last - enough to expire the
    // partition seeded above.
    setRetentionState(1, 6, 1);

    assertThat(runDeletion()).containsExactly(partitionNameMonthsAgo(6));

    assertThat(countAs(connection)).isZero();
    assertThat(cutoffMonthsAgo()).isEqualTo(5);
  }

  /**
   * The forward cap of the same function: a drastically shortened Frist takes effect one calendar
   * month per run instead of erasing years in a single call.
   */
  @Test
  void neverAdvancesFurtherThanOneMonthPerRun() throws Exception {
    createOwnedPartitionMonthsAgo(6);
    insertEntryMonthsAgo(6);
    // Same shortened Frist, but this month's run has already happened: no month has elapsed, so
    // the cutoff must not move at all and nothing may be dropped.
    setRetentionState(1, 6, 0);

    assertThat(runDeletion()).isEmpty();

    assertThat(countAs(connection)).isEqualTo(1);
    assertThat(cutoffMonthsAgo()).isEqualTo(6);
  }

  private void setRetentionState(int retentionMonths, int cutoffMonthsAgo, int lastRunMonthsAgo)
      throws SQLException {
    execute(
        connection,
        "UPDATE diagnostic_context_retention_settings SET retention_months = "
            + retentionMonths
            + ", last_cutoff = date_trunc('month', now()) - interval '"
            + cutoffMonthsAgo
            + " months', last_run_month = (date_trunc('month', now()) - interval '"
            + lastRunMonthsAgo
            + " months')::date WHERE id = 1");
  }

  private List<String> runDeletion() throws SQLException {
    List<String> dropped = new ArrayList<>();
    try (Statement statement = appConnection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT * FROM opaa_diagnostic_context_delete_expired_partitions()")) {
      while (rs.next()) {
        dropped.add(rs.getString(1));
      }
    }
    return dropped;
  }

  private int cutoffMonthsAgo() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT ((extract(year FROM date_trunc('month', now())) - extract(year FROM"
                    + " last_cutoff)) * 12 + (extract(month FROM date_trunc('month', now())) -"
                    + " extract(month FROM last_cutoff)))::int AS months FROM"
                    + " diagnostic_context_retention_settings WHERE id = 1")) {
      assertThat(rs.next()).isTrue();
      return rs.getInt("months");
    }
  }

  private void createOwnedPartitionMonthsAgo(int monthsAgo) throws SQLException {
    String name = partitionNameMonthsAgo(monthsAgo);
    execute(
        connection,
        "CREATE TABLE "
            + name
            + " PARTITION OF diagnostic_context_log FOR VALUES FROM ((date_trunc('month', now())"
            + " - interval '"
            + monthsAgo
            + " months')::date) TO ((date_trunc('month', now()) - interval '"
            + (monthsAgo - 1)
            + " months')::date)");
    // The function drops partitions as opaa_audit_owner, which requires ownership - the changeset
    // hands every partition it creates to that role for the same reason.
    execute(connection, "ALTER TABLE " + name + " OWNER TO " + OWNER_ROLE);
  }

  private String partitionNameMonthsAgo(int monthsAgo) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 'diagnostic_context_log_' || to_char(date_trunc('month', now()) - (? ||"
                + " ' months')::interval, 'YYYY_MM')")) {
      statement.setInt(1, monthsAgo);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getString(1);
      }
    }
  }

  private void insertEntryMonthsAgo(int monthsAgo) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO diagnostic_context_log (event_id, recorded_at, organization_id,"
                + " actor_ref, target_kind, target_ref, test_question, hit_count, hit_refs,"
                + " permission_snapshot, justification) VALUES (?, date_trunc('month', now()) -"
                + " (? || ' months')::interval + interval '2 days', ?, 'actor-pseudonym',"
                + " 'PERMISSION_PROFILE', 'Profil', 'Wo steht die Dienstanweisung?', 0, '',"
                + " 'libraries=[];lockedLibraries=[]', NULL)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setInt(2, monthsAgo);
      statement.setObject(3, ORGANIZATION_ID);
      statement.executeUpdate();
    }
  }

  private String changesetAccount() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT current_user")) {
      assertThat(rs.next()).isTrue();
      return rs.getString(1);
    }
  }

  private List<String> tablePrivilegesOf(String tableName, String grantee) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT DISTINCT privilege_type FROM information_schema.table_privileges WHERE"
                + " table_schema = 'public' AND table_name = ? AND grantee = ?")) {
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

  private List<String> updatableColumnsOf(String tableName, String grantee) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT DISTINCT column_name FROM information_schema.column_privileges WHERE"
                + " table_schema = 'public' AND table_name = ? AND grantee = ? AND privilege_type"
                + " = 'UPDATE'")) {
      statement.setString(1, tableName);
      statement.setString(2, grantee);
      List<String> columns = new ArrayList<>();
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          columns.add(rs.getString(1));
        }
      }
      return columns;
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
