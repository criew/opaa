package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
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
 * Applies Liquibase changelog 022 in isolation, on top of 017 - the same restricted-role pattern
 * {@code Migration017AuditLogTest} establishes (see its own Javadoc for the full reasoning): {@code
 * audit_log} is owned by {@code opaa_audit_owner} after 017, not by the migration/ application
 * account, so this must run as a non-superuser {@code AUDIT_APP_ROLE} - a real Postgres superuser
 * bypasses every ownership check, which would make 022's own {@code SET ROLE opaa_audit_owner} step
 * untested (a superuser can {@code ALTER TABLE} regardless of who owns it, so the changeSet would
 * "work" against a superuser connection even if the {@code SET ROLE} line were missing or wrong).
 *
 * <p>Proves two things #393 code review, finding 1 needs verified against a real database, not only
 * against the Java enum: that {@code chk_audit_log_event_type} actually accepts {@code
 * AUDITOR_ROLE_GRANTED}/{@code AUDITOR_ROLE_REVOKED} after 022 runs, and that every value accepted
 * before 022 is still accepted afterwards (a widen must never accidentally narrow).
 */
@Testcontainers(disabledWithoutDocker = true)
class Migration022AuditorRoleEventTypesTest {

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";
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
    applyChangelog(
        appDatabase, "db/changelog/changes/022-widen-audit-event-type-auditor-role.yaml");
    appConnection.setAutoCommit(true);
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

  private void applyChangelog(Database appDatabase, String changelogPath) throws Exception {
    Liquibase liquibase =
        new Liquibase(changelogPath, new ClassLoaderResourceAccessor(), appDatabase);
    liquibase.update(new Contexts());
  }

  @Test
  void auditorRoleGrantedIsAcceptedAfterTheWideningMigration() throws Exception {
    UUID eventId = insertEntry("AUDITOR_ROLE_GRANTED");

    assertThat(eventExists(eventId)).isTrue();
  }

  @Test
  void auditorRoleRevokedIsAcceptedAfterTheWideningMigration() throws Exception {
    UUID eventId = insertEntry("AUDITOR_ROLE_REVOKED");

    assertThat(eventExists(eventId)).isTrue();
  }

  @Test
  void everyPreExistingEventTypeIsStillAcceptedAfterTheWideningMigration() throws Exception {
    // A widen must never accidentally narrow - spot-checks one representative value from each of
    // the pre-022 categories the constraint's comment groups them into.
    for (String eventType :
        new String[] {
          "ASSET_GRANT_GRANTED",
          "SPACE_CREATED",
          "SYSTEM_ADMIN_ROLE_GRANTED",
          "SYSTEM_ADMIN_ROLE_REVOKED",
          "DIRECTORY_SYNC_RUN_COMPLETED",
          "GOVERNANCE_SETTINGS_CHANGED",
          "AUDIT_LOG_ACCESSED"
        }) {
      UUID eventId = insertEntry(eventType);
      assertThat(eventExists(eventId)).as("event_type %s still accepted", eventType).isTrue();
    }
  }

  @Test
  void aValueOutsideTheWidenedSetIsStillRejected() throws Exception {
    assertThatThrownBy(() -> insertEntry("NOT_A_REAL_EVENT_TYPE"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_audit_log_event_type");
  }

  /**
   * Re-review follow-up (PR #449): 022's own {@code GRANT opaa_audit_owner TO %I WITH SET TRUE} is
   * explicitly temporary - bracketed by a matching {@code REVOKE} at the end of the same changeSet.
   * This proves that revoke actually took effect: after 022 has run, {@code AUDIT_APP_ROLE} must be
   * back to exactly the state {@code
   * Migration017AuditLogTest#theApplicationAccountCannotSwitchItsSessionIdentityToTheOwnerRole()}
   * documents for 017 alone - a bare {@code SET ROLE opaa_audit_owner} fails, because only the
   * automatic, {@code SET}-less {@code CREATEROLE}-time membership remains, not the explicit {@code
   * SET TRUE} grant 022 held only for the duration of its own {@code ALTER TABLE} statements.
   * Without this proof, a changeSet that forgot its closing {@code REVOKE} would look identical
   * from every other test in this class - they only exercise ordinary {@code INSERT}s, which do not
   * need {@code SET ROLE} at all.
   */
  @Test
  void theApplicationAccountCannotSwitchItsSessionIdentityToTheOwnerRoleAfter022Either()
      throws Exception {
    assertThatThrownBy(
            () -> {
              try (Statement statement = appConnection.createStatement()) {
                statement.execute("SET ROLE " + OWNER_ROLE);
              }
            })
        .isInstanceOf(SQLException.class);
  }

  private UUID insertEntry(String eventType) throws SQLException {
    UUID eventId = UUID.randomUUID();
    try (Statement statement = appConnection.createStatement()) {
      statement.execute(
          "INSERT INTO audit_log (event_id, recorded_at, organization_id, actor_kind, actor_ref,"
              + " event_type, object_type, object_id, outcome) VALUES ('"
              + eventId
              + "', now(), '"
              + SEEDED_ORGANIZATION_ID
              + "', 'USER', 'pseud-actor-1', '"
              + eventType
              + "', 'USER_ACCOUNT', 'pseud-subject-1', 'SUCCESS')");
    }
    return eventId;
  }

  private boolean eventExists(UUID eventId) throws SQLException {
    try (Statement statement = appConnection.createStatement();
        var result =
            statement.executeQuery(
                "SELECT count(*) FROM audit_log WHERE event_id = '" + eventId + "'")) {
      result.next();
      return result.getInt(1) == 1;
    }
  }
}
