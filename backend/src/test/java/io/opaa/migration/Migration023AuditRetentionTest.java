package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Applies Liquibase changelog 023 in isolation, on top of 017 - the same restricted-role pattern
 * {@code Migration017AuditLogTest} and {@code Migration022AuditorRoleEventTypesTest} establish (see
 * their own Javadoc, including why the bootstrap fixture stops at changeset 016 rather than
 * bundling 017 into it): {@code audit_log} and (after 023) {@code audit_retention_settings} are
 * owned by {@code opaa_audit_owner}, not the migration/application account, so this must run as a
 * non-superuser {@code AUDIT_APP_ROLE} - a real Postgres superuser bypasses every ownership and ACL
 * check, which would make the whole point of the {@code SECURITY DEFINER} function untested.
 *
 * <p>Proves #395's acceptance criteria directly against real Postgres: the retention bound (1-10
 * years) is rejected outside that range, the deletion function takes no parameters and only ever
 * removes a complete expired monthly partition (never an individual row), the application account
 * can invoke it but cannot itself DROP a partition or write {@code last_cutoff}/{@code
 * last_run_month} directly, and a shortened retention period does not retroactively delete more
 * than the calendar-time-elapsed cap allows in one call - including a burst of rapid repeated calls
 * within the same month, which must not be able to walk the cutoff forward faster than real time
 * actually passing would.
 */
@Testcontainers(disabledWithoutDocker = true)
class Migration023AuditRetentionTest extends AbstractMigrationTest {

  private static final String AUDIT_APP_ROLE = "audit_app_role";
  private static final String AUDIT_APP_ROLE_PASSWORD = "audit_app_role_password";
  private static final String OWNER_ROLE = "opaa_audit_owner";

  @Override
  protected String baseFixtureChangelogPath() {
    // Matching Migration017AuditLogTest and Migration022AuditorRoleEventTypesTest's own
    // precedent exactly: opaa_audit_owner must be created by AUDIT_APP_ROLE itself (017, applied
    // below via appConnection), not by the superuser bootstrap account, so AUDIT_APP_ROLE
    // actually receives the CREATEROLE-time automatic membership 023's ownership-transfer
    // statements rely on. Templating a bundled "through 022" fixture instead (an earlier version
    // of this test did exactly that) leaves AUDIT_APP_ROLE with no relationship whatsoever to
    // opaa_audit_owner and every later GRANT ... WITH SET TRUE fails with "permission denied to
    // grant role".
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
    applyChangelog(appConnection, "db/changelog/changes/023-audit-retention.yaml");
  }

  @AfterEach
  void tearDown() throws SQLException {
    appConnection.close();
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
    // class shares with Migration017AuditLogTest and Migration022AuditorRoleEventTypesTest
    // against the same singleton container - see AbstractMigrationTest#dropRolesIfExist(...).
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

  // --- configuration bounds (#395: "Eine Konfiguration unterhalb eines Jahres oder oberhalb von
  // zehn Jahren wird abgewiesen") ---

  @Test
  void theDefaultRetentionIsThreeYearsAndTheForwardOnlyBaselineIsAlreadySeeded() throws Exception {
    // Code review of #454, finding 3: last_cutoff/last_run_month must not be NULL after the
    // migration - a NULL baseline let the very first call after this migration adopt an uncapped
    // candidate cutoff, defeating "wirkt nur nach vorn" until the second call. Seeding the same
    // 36-month baseline this changeSet's own default configures closes that gap from the start.
    try (Statement statement = bootstrapConnection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT retention_months, last_cutoff, last_run_month"
                    + " FROM audit_retention_settings WHERE id = 1")) {
      assertThat(result.next()).isTrue();
      assertThat(result.getInt("retention_months")).isEqualTo(36);
      Timestamp lastCutoff = result.getTimestamp("last_cutoff");
      LocalDate lastRunMonth = result.getDate("last_run_month").toLocalDate();
      assertThat(lastCutoff).as("last_cutoff must be seeded, not NULL").isNotNull();
      assertThat(lastRunMonth).as("last_run_month must be seeded, not NULL").isNotNull();
      assertThat(lastRunMonth.getDayOfMonth()).isEqualTo(1);
      assertThat(lastRunMonth).isEqualTo(java.time.YearMonth.now().atDay(1));
      assertThat(lastCutoff.toLocalDateTime().toLocalDate())
          .isEqualTo(java.time.YearMonth.now().minusMonths(36).atDay(1));
    }
  }

  @Test
  void aRetentionBelowOneYearIsRejected() throws Exception {
    assertThatThrownBy(
            () -> {
              try (Statement statement = bootstrapConnection.createStatement()) {
                statement.execute(
                    "UPDATE audit_retention_settings SET retention_months = 11 WHERE id = 1");
              }
            })
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_audit_retention_settings_months");
  }

  @Test
  void aRetentionAboveTenYearsIsRejected() throws Exception {
    assertThatThrownBy(
            () -> {
              try (Statement statement = bootstrapConnection.createStatement()) {
                statement.execute(
                    "UPDATE audit_retention_settings SET retention_months = 121 WHERE id = 1");
              }
            })
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_audit_retention_settings_months");
  }

  @Test
  void aSecondRowIsRejectedTheTableIsASingleton() throws Exception {
    assertThatThrownBy(
            () -> {
              try (Statement statement = bootstrapConnection.createStatement()) {
                statement.execute(
                    "INSERT INTO audit_retention_settings"
                        + " (id, retention_months, updated_at) VALUES (2, 36, now())");
              }
            })
        .isInstanceOf(SQLException.class);
  }

  // --- application account privileges on the settings table ---

  @Test
  void theApplicationAccountCanUpdateTheRetentionMonths() throws Exception {
    try (Statement statement = appConnection.createStatement()) {
      statement.execute(
          "UPDATE audit_retention_settings SET retention_months = 60, updated_at = now()"
              + " WHERE id = 1");
    }

    try (Statement statement = appConnection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT retention_months FROM audit_retention_settings WHERE id = 1")) {
      result.next();
      assertThat(result.getInt(1)).isEqualTo(60);
    }
  }

  /**
   * Code review of #454, finding 2: {@code JpaRepository#save} on a dirty-checked entity writes
   * every mapped column, not just the one that logically changed - this is exactly the statement a
   * naive {@code save(settings)} would have issued, and it must fail against the real, restricted
   * grant (it did, in production, before {@code
   * AuditRetentionSettingsRepository#updateRetentionMonths} replaced the {@code save} call). The
   * red half of the reproduction AGENTS.md requires; {@link
   * #theApplicationAccountCanUpdateTheRetentionMonths()} above is the green half - the narrower
   * statement the fix actually issues.
   */
  @Test
  void theNaiveMultiColumnUpdateAHibernateDirtyCheckedSaveWouldIssueIsRejected() throws Exception {
    assertThatThrownBy(
            () -> {
              try (Statement statement = appConnection.createStatement()) {
                statement.execute(
                    "UPDATE audit_retention_settings SET retention_months = 24, last_cutoff ="
                        + " NULL, last_run_month = NULL, updated_at = now() WHERE id = 1");
              }
            })
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
  }

  @Test
  void theApplicationAccountCannotWriteLastCutoffDirectly() throws Exception {
    assertThatThrownBy(
            () -> {
              try (Statement statement = appConnection.createStatement()) {
                statement.execute(
                    "UPDATE audit_retention_settings SET last_cutoff = now() WHERE id = 1");
              }
            })
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
  }

  @Test
  void theApplicationAccountCannotWriteLastRunMonthDirectly() throws Exception {
    assertThatThrownBy(
            () -> {
              try (Statement statement = appConnection.createStatement()) {
                statement.execute(
                    "UPDATE audit_retention_settings SET last_run_month = current_date"
                        + " WHERE id = 1");
              }
            })
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
  }

  @Test
  void theApplicationAccountOwnsNeitherTheSettingsTableNorTheFunction() throws Exception {
    assertThat(ownerOf("audit_retention_settings")).isEqualTo(OWNER_ROLE);
    assertThat(functionOwner()).isEqualTo(OWNER_ROLE);
  }

  // --- the deletion function itself: no parameters, only whole expired partitions ---

  @Test
  void theDeletionFunctionTakesNoParameters() throws Exception {
    try (Statement statement = bootstrapConnection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT pronargs FROM pg_proc WHERE proname ="
                    + " 'opaa_audit_delete_expired_partitions'")) {
      assertThat(result.next()).isTrue();
      assertThat(result.getInt("pronargs")).isZero();
    }
  }

  @Test
  void theApplicationAccountCanCallTheDeletionFunctionAndItDropsAFullyExpiredPartition()
      throws Exception {
    // Older than the migration's own seeded 36-month baseline (finding 3's fix): on the very
    // first call (elapsed_months = 0 since seeding), a shortened retention_months is still capped
    // to that baseline, so only a partition already older than 36 months is guaranteed to drop
    // here regardless of the configured value - see the dedicated forward-only-cap tests below
    // for the shortening behaviour itself.
    String oldPartition = attachSyntheticPartitionMonthsAgo(40);
    setRetentionMonths(12);

    try (Statement statement = appConnection.createStatement()) {
      statement.execute("SELECT * FROM opaa_audit_delete_expired_partitions()");
    }

    assertThat(partitionExists(oldPartition)).isFalse();
  }

  @Test
  void theDeletionFunctionLeavesAPartitionWithinRetentionUntouched() throws Exception {
    String recentPartition = attachSyntheticPartitionMonthsAgo(2);
    setRetentionMonths(36);

    try (Statement statement = appConnection.createStatement()) {
      statement.execute("SELECT * FROM opaa_audit_delete_expired_partitions()");
    }

    assertThat(partitionExists(recentPartition)).isTrue();
  }

  @Test
  void theApplicationAccountCannotDropAPartitionDirectlyEvenThoughItCanCallTheFunction()
      throws Exception {
    String oldPartition = attachSyntheticPartitionMonthsAgo(200);

    // Postgres phrases a DROP TABLE rejection as an ownership check ("must be owner of table
    // ..."), not the "permission denied" wording an ACL-grant rejection (UPDATE/DELETE/TRUNCATE)
    // uses - both are the same underlying guarantee (the application account cannot act on the
    // partition directly), just different Postgres error text for an ownership-only operation.
    assertThatThrownBy(
            () -> {
              try (Statement statement = appConnection.createStatement()) {
                statement.execute("DROP TABLE " + oldPartition);
              }
            })
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("must be owner of table");
  }

  // --- "wirkt nur nach vorn": forward-only, and immune to rapid repeated calls ---

  @Test
  void repeatedCallsWithinTheSameMonthCannotAdvanceTheCutoffFurtherThanASingleCall()
      throws Exception {
    // 121 months ago is already past even the maximum 120-month (10-year) retention. Setting
    // retention to 120 here is a *lengthening* relative to the migration's own seeded 36-month
    // baseline - lengthening is never capped (it only ever protects more, never deletes faster),
    // so the very first call drops it regardless of the seeded baseline.
    String veryOldPartition = attachSyntheticPartitionMonthsAgo(121);
    // 13 months ago is within the still-configured 120-month retention right now, so it survives
    // the first call, and must still survive several more calls made in immediate succession
    // (same calendar month) even after retention is shortened to its 12-month floor.
    String thirteenMonthsAgo = attachSyntheticPartitionMonthsAgo(13);
    setRetentionMonths(120);

    try (Statement statement = appConnection.createStatement()) {
      statement.execute("SELECT * FROM opaa_audit_delete_expired_partitions()");
    }
    assertThat(partitionExists(veryOldPartition)).isFalse();
    assertThat(partitionExists(thirteenMonthsAgo)).isTrue();

    setRetentionMonths(12);
    for (int i = 0; i < 5; i++) {
      try (Statement statement = appConnection.createStatement()) {
        statement.execute("SELECT * FROM opaa_audit_delete_expired_partitions()");
      }
    }

    assertThat(partitionExists(thirteenMonthsAgo))
        .as(
            "a partition 13 months old must not be deleted by repeatedly calling the function"
                + " within the same calendar month, even after retention was shortened to its"
                + " 12-month floor - the cutoff may only advance as far as real elapsed time"
                + " allows, not once per call")
        .isTrue();
  }

  /**
   * Code review of #454, finding 3 - the regression test: before this fix, the migration seeded
   * {@code last_cutoff}/{@code last_run_month} as {@code NULL}, so a retention shortened *before*
   * the very first call to the function was applied fully retroactively (the {@code IS NULL} branch
   * adopts {@code candidate_cutoff} uncapped). Seeding the same baseline the migration's own
   * default configures means the cap already applies on this first call: a partition 13 months old
   * must survive a shortening to the 12-month floor made immediately after the migration, with no
   * prior call to the function at all.
   */
  @Test
  void aRetentionShortenedBeforeTheVeryFirstCallIsStillCappedByTheSeededBaseline()
      throws Exception {
    String thirteenMonthsAgo = attachSyntheticPartitionMonthsAgo(13);

    setRetentionMonths(12);
    try (Statement statement = appConnection.createStatement()) {
      statement.execute("SELECT * FROM opaa_audit_delete_expired_partitions()");
    }

    assertThat(partitionExists(thirteenMonthsAgo))
        .as(
            "a 13-month-old partition must survive a retention shortened to 12 months immediately"
                + " after the migration, before any prior call to the deletion function ever ran")
        .isTrue();
  }

  /**
   * Code review of #454, finding 1 - the regression test for the {@code pg_temp} shadowing attack:
   * the application account creates a temporary table with the same name as the real settings
   * table, seeds it with an out-of-bounds retention (no {@code CHECK} constraint applies to a
   * session-local temp table) and a {@code NULL} baseline, then calls the deletion function. Before
   * the {@code search_path}/schema-qualification fix, the function resolved the unqualified table
   * name against this shadow row and deleted far more than the real, database-enforced 36-month
   * default would ever allow. After the fix, the function must ignore the shadow table entirely and
   * use the real row - a partition well within the real 36-month default must survive, and the real
   * row's own {@code retention_months} must remain untouched.
   */
  @Test
  void theApplicationAccountCannotShadowTheSettingsTableWithATemporaryTableOfTheSameName()
      throws Exception {
    String recentPartition = attachSyntheticPartitionMonthsAgo(2);

    try (Statement statement = appConnection.createStatement()) {
      statement.execute(
          "CREATE TEMP TABLE audit_retention_settings (id integer PRIMARY KEY,"
              + " retention_months integer, last_cutoff timestamptz, last_run_month date,"
              + " updated_at timestamptz)");
      statement.execute("INSERT INTO audit_retention_settings VALUES (1, 1, NULL, NULL, now())");
      statement.execute("GRANT ALL ON pg_temp.audit_retention_settings TO opaa_audit_owner");
      statement.execute("SELECT * FROM opaa_audit_delete_expired_partitions()");
    }

    assertThat(partitionExists(recentPartition))
        .as(
            "a partition well within the real 36-month default must survive - the function must"
                + " never resolve audit_retention_settings against a same-named temp table the"
                + " application account created")
        .isTrue();
    try (Statement statement = appConnection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT retention_months FROM public.audit_retention_settings WHERE id = 1")) {
      result.next();
      assertThat(result.getInt(1))
          .as("the real, database-owned settings row must be untouched by the shadow attack")
          .isEqualTo(36);
    }
  }

  private String attachSyntheticPartitionMonthsAgo(int monthsAgo) throws SQLException {
    String partitionName;
    try (Statement statement = bootstrapConnection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT 'audit_log_' || to_char("
                    + "date_trunc('month', now()) - interval '"
                    + monthsAgo
                    + " months', 'YYYY_MM')")) {
      result.next();
      partitionName = result.getString(1);
    }
    try (Statement statement = bootstrapConnection.createStatement()) {
      statement.execute(
          "DO $$"
              + " DECLARE m date := (date_trunc('month', now())"
              + "   - interval '"
              + monthsAgo
              + " months')::date;"
              + " BEGIN"
              + "   IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = '"
              + partitionName
              + "') THEN"
              + "     EXECUTE format('CREATE TABLE %I PARTITION OF audit_log FOR VALUES FROM"
              + " (%L) TO (%L)', '"
              + partitionName
              + "', m, m + interval '1 month');"
              + "     EXECUTE format('ALTER TABLE %I OWNER TO opaa_audit_owner', '"
              + partitionName
              + "');"
              + "   END IF;"
              + " END $$;");
    }
    return partitionName;
  }

  private void setRetentionMonths(int months) throws SQLException {
    try (Statement statement = bootstrapConnection.createStatement()) {
      statement.execute(
          "UPDATE audit_retention_settings SET retention_months = " + months + " WHERE id = 1");
    }
  }

  private boolean partitionExists(String tableName) throws SQLException {
    try (Statement statement = bootstrapConnection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM pg_class WHERE relname = '" + tableName + "'")) {
      result.next();
      return result.getInt(1) > 0;
    }
  }

  private String ownerOf(String tableName) throws SQLException {
    try (Statement statement = bootstrapConnection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE relname = '"
                    + tableName
                    + "'")) {
      result.next();
      return result.getString(1);
    }
  }

  private String functionOwner() throws SQLException {
    try (Statement statement = bootstrapConnection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT pg_get_userbyid(proowner) FROM pg_proc WHERE proname ="
                    + " 'opaa_audit_delete_expired_partitions'")) {
      result.next();
      return result.getString(1);
    }
  }
}
