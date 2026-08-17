package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.audit.AuditEventType;
import io.opaa.audit.AuditObjectType;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
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
 * Applies Liquibase changelog 017 in isolation against a database built from the real, versioned
 * changelog through changeSet 016 - the same pattern as {@code
 * Migration016VectorStoreLibraryMetadataTest}, with {@code test-master-through-016.yaml} as the
 * pre-migration fixture.
 *
 * <p><b>Why the changelog is applied as a dedicated, non-superuser role ({@code AUDIT_APP_ROLE}),
 * not as the container's bootstrap account:</b> 017's last changeSet revokes privileges from {@code
 * current_user} - the role whose JDBC connection is executing the changelog at that point (see that
 * changeSet's comment for why: this project runs Liquibase and the application under the same
 * database role, so the two are one and the same account by design). Testcontainers' {@code
 * PostgreSQLContainer} bootstrap account is a Postgres superuser, and a superuser bypasses every
 * ACL check unconditionally - {@code REVOKE} against a superuser is a structural no-op, not a bug
 * in the changeSet. Running the changelog as {@code AUDIT_APP_ROLE} instead - an ordinary role
 * created here with just the privileges migrations actually need (schema {@code CREATE}, {@code
 * REFERENCES} on the two tables it adds foreign keys to) - exercises the changeSet exactly the way
 * a correctly hardened production deployment would (a non-superuser application account), which is
 * the only way this changeSet's effect can be observed at all.
 *
 * <p>The gap this leaves - that the project's own shipped {@code docker-compose.yml} still
 * bootstraps its single Postgres account as a superuser, under which this protection would
 * currently be inert - is filed as #426; it is not something a single migration file can fix
 * without risking the rest of the schema's setup (extension creation, ownership of every other
 * table), which is why it is intentionally out of scope here.
 */
@Testcontainers(disabledWithoutDocker = true)
class Migration017AuditLogTest {

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";
  private static final String AUDIT_APP_ROLE = "audit_app_role";
  private static final String AUDIT_APP_ROLE_PASSWORD = "audit_app_role_password";

  /**
   * The full column set of the standard record, per #391 - deliberately excludes any network,
   * device/browser or location field (docs/features/security-and-compliance.md#der-protokollsatz).
   */
  private static final Set<String> EXPECTED_COLUMNS =
      Set.of(
          "event_id",
          "recorded_at",
          "organization_id",
          "actor_kind",
          "actor_ref",
          "event_type",
          "object_type",
          "object_id",
          "object_label",
          "subject_kind",
          "subject_ref",
          "before",
          "after",
          "outcome",
          "reason",
          "correlation_ref");

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
    Liquibase auditLogLiquibase =
        new Liquibase(
            "db/changelog/changes/017-audit-log.yaml",
            new ClassLoaderResourceAccessor(),
            appDatabase);
    auditLogLiquibase.update(new Contexts());
    appConnection.setAutoCommit(true);
  }

  @AfterEach
  void tearDown() throws SQLException {
    appConnection.close();
    bootstrapConnection.setAutoCommit(true);
    try (Statement statement = bootstrapConnection.createStatement()) {
      statement.execute("DROP SCHEMA public CASCADE");
      statement.execute("CREATE SCHEMA public");
      // A freshly initdb'd database's public schema carries an implicit "USAGE granted to PUBLIC"
      // default that only initdb itself applies - a manually recreated schema does not get it back
      // automatically. Without this, every test after the first in this class would fail with
      // "no schema has been selected to create in" the moment AUDIT_APP_ROLE tries to resolve the
      // unqualified table name in its own CREATE TABLE statement.
      statement.execute("GRANT USAGE ON SCHEMA public TO PUBLIC");
      statement.execute("DROP ROLE IF EXISTS " + AUDIT_APP_ROLE);
    }
    bootstrapConnection.close();
  }

  /**
   * A role with just enough privilege to run migrations that create tables and foreign keys in the
   * public schema, and nothing more - PostgreSQL 15+ no longer grants CREATE on the public schema
   * to every role by default, so it must be granted explicitly here. Also needs read/write on
   * Liquibase's own tracking tables ({@code databasechangelog}/{@code databasechangeloglock}):
   * those were created and are owned by the bootstrap connection when the fixture changelog ({@code
   * test-master-through-016.yaml}) ran, and Liquibase reads and appends to the very same tables -
   * not per-role copies - when applying 017 on the second connection.
   */
  private void createNonSuperuserApplicationRole() throws SQLException {
    try (Statement statement = bootstrapConnection.createStatement()) {
      statement.execute(
          "CREATE ROLE " + AUDIT_APP_ROLE + " LOGIN PASSWORD '" + AUDIT_APP_ROLE_PASSWORD + "'");
      statement.execute("GRANT CREATE ON SCHEMA public TO " + AUDIT_APP_ROLE);
      statement.execute("GRANT REFERENCES ON organizations TO " + AUDIT_APP_ROLE);
      statement.execute("GRANT REFERENCES ON users TO " + AUDIT_APP_ROLE);
      statement.execute(
          "GRANT SELECT, INSERT, UPDATE, DELETE ON databasechangelog, databasechangeloglock TO "
              + AUDIT_APP_ROLE);
    }
  }

  @Test
  void aFullRecordCanBeWrittenAndReadBackByTheApplicationAccount() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID subjectUserPseudonym = UUID.randomUUID();
    try (Statement statement = appConnection.createStatement()) {
      statement.execute(
          "INSERT INTO audit_log (event_id, organization_id, actor_kind, actor_ref, event_type,"
              + " object_type, object_id, object_label, subject_kind, subject_ref, before, after,"
              + " outcome, reason, correlation_ref) VALUES ('"
              + eventId
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', 'USER', 'pseud-actor-1', 'ASSET_GRANT_REVOKED', 'KNOWLEDGE_LIBRARY',"
              + " 'lib-personalvorgaenge', 'Personalvorgaenge', 'GROUP', '"
              + subjectUserPseudonym
              + "', '{\"role\":\"READER\"}', NULL, 'SUCCESS', 'anlassbezogene Klaerung',"
              + " 'sync-2026-02-16-06')");
    }

    try (Statement statement = appConnection.createStatement();
        ResultSet result =
            statement.executeQuery("SELECT * FROM audit_log WHERE event_id = '" + eventId + "'")) {
      assertThat(result.next()).isTrue();
      assertThat(result.getString("organization_id")).isEqualTo(SEEDED_ORGANIZATION_ID);
      assertThat(result.getString("actor_kind")).isEqualTo("USER");
      assertThat(result.getString("actor_ref")).isEqualTo("pseud-actor-1");
      assertThat(result.getString("event_type")).isEqualTo("ASSET_GRANT_REVOKED");
      assertThat(result.getString("object_type")).isEqualTo("KNOWLEDGE_LIBRARY");
      assertThat(result.getString("object_id")).isEqualTo("lib-personalvorgaenge");
      assertThat(result.getString("object_label")).isEqualTo("Personalvorgaenge");
      assertThat(result.getString("subject_kind")).isEqualTo("GROUP");
      assertThat(result.getString("subject_ref")).isEqualTo(subjectUserPseudonym.toString());
      assertThat(result.getString("before")).isEqualTo("{\"role\":\"READER\"}");
      assertThat(result.getString("after")).isNull();
      assertThat(result.getString("outcome")).isEqualTo("SUCCESS");
      assertThat(result.getString("reason")).isEqualTo("anlassbezogene Klaerung");
      assertThat(result.getString("correlation_ref")).isEqualTo("sync-2026-02-16-06");
      assertThat(result.next()).isFalse();
    }
  }

  @Test
  void theApplicationAccountCannotUpdateAWrittenEntry() throws Exception {
    UUID eventId = insertMinimalEntry();

    assertThatThrownBy(
            () -> {
              try (Statement statement = appConnection.createStatement()) {
                statement.execute(
                    "UPDATE audit_log SET outcome = 'FAILURE' WHERE event_id = '" + eventId + "'");
              }
            })
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
  }

  @Test
  void theApplicationAccountCannotDeleteAWrittenEntry() throws Exception {
    UUID eventId = insertMinimalEntry();

    assertThatThrownBy(
            () -> {
              try (Statement statement = appConnection.createStatement()) {
                statement.execute("DELETE FROM audit_log WHERE event_id = '" + eventId + "'");
              }
            })
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
  }

  @Test
  void theApplicationAccountCannotTruncateTheTable() throws Exception {
    insertMinimalEntry();

    assertThatThrownBy(
            () -> {
              try (Statement statement = appConnection.createStatement()) {
                statement.execute("TRUNCATE TABLE audit_log");
              }
            })
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
  }

  @Test
  void theStandardRecordCarriesExactlyTheSpecifiedColumnsNoNetworkAddress() throws Exception {
    Set<String> actualColumns = new HashSet<>();
    try (Statement statement = bootstrapConnection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT column_name FROM information_schema.columns"
                    + " WHERE table_name = 'audit_log'")) {
      while (result.next()) {
        actualColumns.add(result.getString("column_name"));
      }
    }

    assertThat(actualColumns).isEqualTo(EXPECTED_COLUMNS);
  }

  @Test
  void theTableIsPartitionedByMonthWithAWorkingDefaultPartition() throws Exception {
    assertThat(relKind("audit_log")).isEqualTo("p");
    assertThat(partitionCount()).isGreaterThan(1);
    assertThat(partitionExists("audit_log_default")).isTrue();
  }

  @Test
  void theEventTypeCheckConstraintMatchesTheJavaEnumExactly() throws Exception {
    Set<String> constraintValues = checkConstraintValues("chk_audit_log_event_type");
    Set<String> enumValues = new HashSet<>();
    for (AuditEventType eventType : AuditEventType.values()) {
      enumValues.add(eventType.name());
    }

    assertThat(constraintValues).isEqualTo(enumValues);
  }

  @Test
  void theObjectTypeCheckConstraintMatchesTheJavaEnumExactly() throws Exception {
    Set<String> constraintValues = checkConstraintValues("chk_audit_log_object_type");
    Set<String> enumValues = new HashSet<>();
    for (AuditObjectType objectType : AuditObjectType.values()) {
      enumValues.add(objectType.name());
    }

    assertThat(constraintValues).isEqualTo(enumValues);
  }

  @Test
  void anEventTypeOutsideTheClosedListIsRejected() throws Exception {
    assertThatThrownBy(() -> insertMinimalEntry("NOT_A_REAL_EVENT_TYPE"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_audit_log_event_type");
  }

  @Test
  void aSubjectRefWithoutASubjectKindIsRejected() throws Exception {
    UUID eventId = UUID.randomUUID();
    assertThatThrownBy(
            () -> {
              try (Statement statement = bootstrapConnection.createStatement()) {
                statement.execute(
                    "INSERT INTO audit_log (event_id, organization_id, actor_kind, actor_ref,"
                        + " event_type, object_type, object_id, subject_ref, outcome) VALUES ('"
                        + eventId
                        + "', '"
                        + SEEDED_ORGANIZATION_ID
                        + "', 'USER', 'pseud-actor-1', 'SPACE_CREATED', 'SPACE', 'space-1',"
                        + " 'pseud-subject-1', 'SUCCESS')");
              }
            })
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_audit_log_subject");
  }

  @Test
  void deletingThePseudonymMappingDoesNotChangeTheWrittenEntry() throws Exception {
    UUID userId = insertUser();
    UUID pseudonymId = UUID.randomUUID();
    try (Statement statement = bootstrapConnection.createStatement()) {
      statement.execute(
          "INSERT INTO audit_actor_pseudonyms (pseudonym_id, user_id, organization_id) VALUES ('"
              + pseudonymId
              + "', '"
              + userId
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "')");
    }
    UUID eventId = insertMinimalEntryWithActorRef(pseudonymId.toString());

    // Deleting a user cascades to its pseudonym mapping (fk_audit_actor_pseudonyms_user, ON
    // DELETE CASCADE) - docs/features/security-and-compliance.md#unveränderlichkeit-und-löschrecht
    // says the protocol entry itself must survive unchanged.
    try (Statement statement = bootstrapConnection.createStatement()) {
      statement.execute("DELETE FROM users WHERE id = '" + userId + "'");
    }

    assertThat(pseudonymExists(pseudonymId)).isFalse();
    try (Statement statement = bootstrapConnection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT actor_ref FROM audit_log WHERE event_id = '" + eventId + "'")) {
      assertThat(result.next()).isTrue();
      assertThat(result.getString("actor_ref")).isEqualTo(pseudonymId.toString());
    }
  }

  private UUID insertMinimalEntry() throws SQLException {
    return insertMinimalEntry("SPACE_CREATED");
  }

  private UUID insertMinimalEntry(String eventType) throws SQLException {
    return insertMinimalEntryWithActorRef("pseud-actor-1", eventType);
  }

  private UUID insertMinimalEntryWithActorRef(String actorRef) throws SQLException {
    return insertMinimalEntryWithActorRef(actorRef, "SPACE_CREATED");
  }

  private UUID insertMinimalEntryWithActorRef(String actorRef, String eventType)
      throws SQLException {
    UUID eventId = UUID.randomUUID();
    try (Statement statement = appConnection.createStatement()) {
      statement.execute(
          "INSERT INTO audit_log (event_id, organization_id, actor_kind, actor_ref, event_type,"
              + " object_type, object_id, outcome) VALUES ('"
              + eventId
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', 'USER', '"
              + actorRef
              + "', '"
              + eventType
              + "', 'SPACE', 'space-1', 'SUCCESS')");
    }
    return eventId;
  }

  private UUID insertUser() throws SQLException {
    UUID userId = UUID.randomUUID();
    try (Statement statement = bootstrapConnection.createStatement()) {
      statement.execute(
          "INSERT INTO users (id, subject, issuer, organization_id) VALUES ('"
              + userId
              + "', 'subject-"
              + userId
              + "', 'test-issuer', '"
              + SEEDED_ORGANIZATION_ID
              + "')");
    }
    return userId;
  }

  private boolean pseudonymExists(UUID pseudonymId) throws SQLException {
    try (Statement statement = bootstrapConnection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM audit_actor_pseudonyms WHERE pseudonym_id = '"
                    + pseudonymId
                    + "'")) {
      result.next();
      return result.getInt(1) > 0;
    }
  }

  private String relKind(String tableName) throws SQLException {
    try (Statement statement = bootstrapConnection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT relkind FROM pg_class WHERE relname = '" + tableName + "'")) {
      result.next();
      return result.getString(1);
    }
  }

  private int partitionCount() throws SQLException {
    try (Statement statement = bootstrapConnection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM pg_inherits"
                    + " JOIN pg_class parent ON pg_inherits.inhparent = parent.oid"
                    + " WHERE parent.relname = 'audit_log'")) {
      result.next();
      return result.getInt(1);
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

  /** Extracts the quoted string literals out of a CHECK (... IN (...)) constraint definition. */
  private Set<String> checkConstraintValues(String constraintName) throws SQLException {
    String definition;
    try (Statement statement = bootstrapConnection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = '"
                    + constraintName
                    + "'")) {
      result.next();
      definition = result.getString(1);
    }

    Set<String> values = new HashSet<>();
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("'([A-Z_]+)'").matcher(definition);
    while (matcher.find()) {
      values.add(matcher.group(1));
    }
    return values;
  }
}
