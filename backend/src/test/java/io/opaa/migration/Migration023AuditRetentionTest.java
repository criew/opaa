package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

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
class Migration023AuditRetentionTest {

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));

  private static final String AUDIT_APP_ROLE = "audit_app_role";
  private static final String AUDIT_APP_ROLE_PASSWORD = "audit_app_role_password";
  private static final String OWNER_ROLE = "opaa_audit_owner";

  private Connection bootstrapConnection;
  private Connection appConnection;
  private Database database;

  @BeforeEach
  void setUp() throws Exception {
    bootstrapConnection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    database =
        DatabaseFactory.getInstance()
            .findCorrectDatabaseImplementation(new JdbcConnection(bootstrapConnection));

    // Bootstrap only through 016 (as bootstrapConnection/superuser) - matching
    // Migration017AuditLogTest
    // and Migration022AuditorRoleEventTypesTest's own precedent exactly: opaa_audit_owner must be
    // created by AUDIT_APP_ROLE itself (017, applied below via appConnection), not by the
    // superuser bootstrap account, so AUDIT_APP_ROLE actually receives the CREATEROLE-time
    // automatic membership 023's ownership-transfer statements rely on. Applying a bundled
    // "through 022" fixture as the superuser bootstrap account instead (an earlier version of this
    // test did exactly that) leaves AUDIT_APP_ROLE with no relationship whatsoever to
    // opaa_audit_owner and every later GRANT ... WITH SET TRUE fails with "permission denied to
    // grant role".
    Liquibase liquibase =
        new Liquibase(
            "db/changelog/test-master-through-016.yaml",
            new ClassLoaderResourceAccessor(),
            database);
    liquibase.update(new Contexts());
    bootstrapConnection.setAutoCommit(true);

    createNonSuperuserApplicationRole();

    appConnection =
        DriverManager.getConnection(postgres.getJdbcUrl(), AUDIT_APP_ROLE, AUDIT_APP_ROLE_PASSWORD);
    Database appDatabase =
        DatabaseFactory.getInstance()
            .findCorrectDatabaseImplementation(new JdbcConnection(appConnection));
    applyChangelog(appDatabase, "db/changelog/changes/017-audit-log.yaml");
    applyChangelog(appDatabase, "db/changelog/changes/023-audit-retention.yaml");
    appConnection.setAutoCommit(true);
  }

  private void applyChangelog(Database appDatabase, String changelogPath) throws Exception {
    Liquibase liquibase =
        new Liquibase(changelogPath, new ClassLoaderResourceAccessor(), appDatabase);
    liquibase.update(new Contexts());
  }

  @AfterEach
  void tearDown() throws SQLException {
    appConnection.close();
    bootstrapConnection.setAutoCommit(true);
    try (Statement statement = bootstrapConnection.createStatement()) {
      statement.execute("DROP SCHEMA public CASCADE");
      statement.execute("CREATE SCHEMA public");
      statement.execute("GRANT USAGE ON SCHEMA public TO PUBLIC");
      statement.execute("DROP ROLE IF EXISTS " + AUDIT_APP_ROLE);
      statement.execute("DROP ROLE IF EXISTS " + OWNER_ROLE);
    }
    bootstrapConnection.close();
  }

  private void createNonSuperuserApplicationRole() throws SQLException {
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
  void theDefaultRetentionIsThreeYears() throws Exception {
    try (Statement statement = bootstrapConnection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT retention_months, last_cutoff, last_run_month"
                    + " FROM audit_retention_settings WHERE id = 1")) {
      assertThat(result.next()).isTrue();
      assertThat(result.getInt("retention_months")).isEqualTo(36);
      assertThat(result.getObject("last_cutoff")).isNull();
      assertThat(result.getObject("last_run_month")).isNull();
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
    String oldPartition = attachSyntheticPartitionMonthsAgo(20);
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
    // 121 months ago is already past even the maximum 120-month (10-year) retention, so the very
    // first call adopts candidate_cutoff uncapped (last_cutoff was NULL) and drops it.
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
