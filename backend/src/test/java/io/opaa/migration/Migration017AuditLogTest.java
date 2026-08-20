package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.audit.ActorKind;
import io.opaa.audit.AuditEventType;
import io.opaa.audit.AuditObjectType;
import io.opaa.audit.AuditOutcome;
import io.opaa.audit.AuditSubjectKind;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies Liquibase changelog 017 in isolation against a database built from the real, versioned
 * changelog through changeSet 016 - the same pattern as {@code
 * Migration016VectorStoreLibraryMetadataTest}, with {@code test-master-through-016.yaml} as the
 * pre-migration fixture.
 *
 * <p><b>Why the changelog is applied as a dedicated, non-superuser role ({@code AUDIT_APP_ROLE}),
 * not as the container's bootstrap account:</b> 017's last changeSet transfers ownership away from
 * {@code current_user} - the role whose JDBC connection is executing the changelog at that point
 * (see that changeSet's comment and ADR-0015 for why: this project runs Liquibase and the
 * application under the same database role, so the two are one and the same account by design).
 * Testcontainers' {@code PostgreSQLContainer} bootstrap account is a Postgres superuser, and a
 * superuser bypasses every ownership and ACL check unconditionally - none of this changeSet's
 * effect would be observable against it. Running the changelog as {@code AUDIT_APP_ROLE} instead -
 * an ordinary role created here with just the privileges migrations actually need (schema {@code
 * CREATE}, {@code REFERENCES} on the two tables it adds foreign keys to, and {@code CREATEROLE} to
 * create {@code opaa_audit_owner} - see ADR-0015) - exercises the changeSet exactly the way a
 * correctly hardened production deployment would.
 *
 * <p>The gap this leaves - that the project's own shipped {@code docker-compose.yml} still
 * bootstraps its single Postgres account as a superuser, and that a hardened deployment should not
 * leave {@code CREATEROLE} on the runtime account indefinitely - is filed as #426 (updated per
 * ADR-0015's review); it is not something a single migration file can fix without risking the rest
 * of the schema's setup (extension creation, ownership of every other table), which is why it is
 * intentionally out of scope here.
 */
class Migration017AuditLogTest extends AbstractMigrationTest {

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";
  private static final String AUDIT_APP_ROLE = "audit_app_role";
  private static final String AUDIT_APP_ROLE_PASSWORD = "audit_app_role_password";
  private static final String OWNER_ROLE = "opaa_audit_owner";

  /** Used only by {@link #theEscalationFailsWhenTheOwnerRoleIsProvisionedByASeparateIdentity()}. */
  private static final String DEMO_OWNER_ROLE = "opaa_audit_owner_demo";

  private static final String DEMO_TABLE = "audit_log_ownership_demo";

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

  @Override
  protected String baseFixtureChangelogPath() {
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
  }

  @AfterEach
  void tearDown() throws SQLException {
    appConnection.close();
    // bootstrapConnection is itself a connection to this test's per-test database, so it must be
    // closed before that database is dropped below.
    bootstrapConnection.close();
    // AUDIT_APP_ROLE/OWNER_ROLE/DEMO_OWNER_ROLE are cluster-wide roles (issue #497's
    // AbstractMigrationTest Javadoc explains why they cannot live in the per-class template
    // database) - they must keep being dropped per test method here, exactly as before dropping
    // the whole per-test database was introduced. DROP ROLE fails while a role still owns
    // objects anywhere in the cluster, so this test's own per-test database - the only database
    // that ever gave these roles ownership of anything - must be dropped first, and the DROP ROLE
    // statements below use a fresh connection to the container's stable bootstrap database rather
    // than the now-closed, now-dropped bootstrapConnection.
    dropCurrentDatabaseNow();
    try (Connection admin = adminConnection();
        Statement statement = admin.createStatement()) {
      statement.execute("DROP ROLE IF EXISTS " + AUDIT_APP_ROLE);
      statement.execute("DROP ROLE IF EXISTS " + OWNER_ROLE);
      statement.execute("DROP ROLE IF EXISTS " + DEMO_OWNER_ROLE);
    }
  }

  /**
   * A role with just enough privilege to run migrations that create tables and foreign keys in the
   * public schema, plus {@code CREATEROLE} - needed to create {@code opaa_audit_owner} and grant
   * itself temporary membership in it (see ADR-0015 and 017-restrict-audit-log-privileges) - and
   * nothing more. PostgreSQL 15+ no longer grants {@code CREATE} on the public schema to every role
   * by default, so it must be granted explicitly here, and {@code WITH GRANT OPTION}: {@code ALTER
   * TABLE ... OWNER TO opaa_audit_owner} requires the new owner itself to hold {@code CREATE} on
   * the schema (membership in a role that holds it is not enough), so the migration re-grants
   * {@code CREATE} to the role it just created - which needs the grantor to hold the option, not
   * just the privilege. Also needs read/write on Liquibase's own tracking tables ({@code
   * databasechangelog}/{@code databasechangeloglock}): those were created and are owned by the
   * bootstrap connection when the fixture changelog ({@code test-master-through-016.yaml}) ran, and
   * Liquibase reads and appends to the very same tables - not per-role copies - when applying 017
   * on the second connection.
   */
  private void createNonSuperuserApplicationRole() throws SQLException {
    // Defensive cleanup (issue #497): AUDIT_APP_ROLE/OWNER_ROLE/DEMO_OWNER_ROLE are cluster-wide
    // role names this class shares with Migration022AuditorRoleEventTypesTest and
    // Migration023AuditRetentionTest against the same singleton container - see
    // AbstractMigrationTest#dropRolesIfExist(...).
    dropRolesIfExist(bootstrapConnection, AUDIT_APP_ROLE, OWNER_ROLE, DEMO_OWNER_ROLE);
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

  @Test
  void aFullRecordCanBeWrittenAndReadBackByTheApplicationAccount() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID subjectUserPseudonym = UUID.randomUUID();
    try (Statement statement = appConnection.createStatement()) {
      statement.execute(
          "INSERT INTO audit_log (event_id, recorded_at, organization_id, actor_kind, actor_ref,"
              + " event_type, object_type, object_id, object_label, subject_kind, subject_ref,"
              + " before, after, outcome, reason, correlation_ref) VALUES ('"
              + eventId
              + "', now(), '"
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
  void theApplicationAccountCannotUpdateAWrittenEntryOnTheParentTable() throws Exception {
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
  void theApplicationAccountCannotDeleteAWrittenEntryOnTheParentTable() throws Exception {
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
  void theApplicationAccountCannotTruncateTheParentTable() throws Exception {
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

  // --- direct-partition attacks (review finding 1: a parent-table-only REVOKE/GRANT does not
  // protect a partition addressed by its own name, since every partition carries its own ACL) ---

  @Test
  void theApplicationAccountCannotUpdateAWrittenEntryDirectlyOnItsPartition() throws Exception {
    UUID eventId = insertMinimalEntry();
    String partition = partitionNameOf(eventId);

    assertThatThrownBy(
            () -> {
              try (Statement statement = appConnection.createStatement()) {
                statement.execute(
                    "UPDATE "
                        + partition
                        + " SET outcome = 'FAILURE' WHERE event_id = '"
                        + eventId
                        + "'");
              }
            })
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
  }

  @Test
  void theApplicationAccountCannotDeleteAWrittenEntryDirectlyOnItsPartition() throws Exception {
    UUID eventId = insertMinimalEntry();
    String partition = partitionNameOf(eventId);

    assertThatThrownBy(
            () -> {
              try (Statement statement = appConnection.createStatement()) {
                statement.execute(
                    "DELETE FROM " + partition + " WHERE event_id = '" + eventId + "'");
              }
            })
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
  }

  @Test
  void theApplicationAccountCannotTruncateAPartitionDirectly() throws Exception {
    UUID eventId = insertMinimalEntry();
    String partition = partitionNameOf(eventId);

    assertThatThrownBy(
            () -> {
              try (Statement statement = appConnection.createStatement()) {
                statement.execute("TRUNCATE TABLE " + partition);
              }
            })
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
  }

  // --- ownership attacks (review finding 2: an owner can always undo a plain REVOKE) ---

  /**
   * "GRANT ALL ... TO <no grant option>" is a Postgres quirk worth calling out: unlike a
   * single-privilege GRANT, it does not raise a hard error when the grantor holds no grantable
   * privilege at all - it silently grants nothing and emits a non-fatal {@code WARNING} the JDBC
   * driver does not surface as an exception. Asserting on the GRANT statement itself would
   * therefore be asserting on an implementation detail, not on the guarantee that matters: whether
   * the self-grant attempt actually changed anything. So this test executes the GRANT (ignoring
   * whatever it does or does not throw) and then re-proves the write restriction directly
   * afterwards - if the self-grant had worked, this UPDATE would now succeed.
   */
  @Test
  void theApplicationAccountCannotGrantItselfPrivilegesBack() throws Exception {
    UUID eventId = insertMinimalEntry();
    try (Statement statement = appConnection.createStatement()) {
      statement.execute("GRANT ALL ON TABLE audit_log TO " + AUDIT_APP_ROLE);
    } catch (SQLException expectedOrIgnored) {
      // Either outcome (a hard error or the silent "no privileges granted" warning path) is
      // acceptable here - what this test actually verifies follows below.
    }

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
  void theApplicationAccountCannotDropACheckConstraint() throws Exception {
    assertThatThrownBy(
            () -> {
              try (Statement statement = appConnection.createStatement()) {
                statement.execute("ALTER TABLE audit_log DROP CONSTRAINT chk_audit_log_event_type");
              }
            })
        .isInstanceOf(SQLException.class);
  }

  @Test
  void theApplicationAccountCannotDetachAPartition() throws Exception {
    UUID eventId = insertMinimalEntry();
    String partition = partitionNameOf(eventId);

    assertThatThrownBy(
            () -> {
              try (Statement statement = appConnection.createStatement()) {
                statement.execute("ALTER TABLE audit_log DETACH PARTITION " + partition);
              }
            })
        .isInstanceOf(SQLException.class);
  }

  @Test
  void theApplicationAccountCannotDropTheTable() throws Exception {
    assertThatThrownBy(
            () -> {
              try (Statement statement = appConnection.createStatement()) {
                statement.execute("DROP TABLE audit_log");
              }
            })
        .isInstanceOf(SQLException.class);
  }

  // --- ownership itself ---

  @Test
  void theApplicationAccountOwnsNeitherTheParentNorAnyPartitionNorThePseudonymTable()
      throws Exception {
    assertThat(ownerOf("audit_log")).isEqualTo(OWNER_ROLE);
    assertThat(ownerOf("audit_actor_pseudonyms")).isEqualTo(OWNER_ROLE);
    UUID eventId = insertMinimalEntry();
    assertThat(ownerOf(partitionNameOf(eventId))).isEqualTo(OWNER_ROLE);
  }

  /**
   * {@code SET ROLE opaa_audit_owner} itself is blocked for the application account - true, but not
   * the whole story (see the two tests below). Kept as its own assertion because it is still a real
   * property: nothing here grants {@code audit_app_role} the ability to switch its session identity
   * to {@code opaa_audit_owner} outright.
   */
  @Test
  void theApplicationAccountCannotSwitchItsSessionIdentityToTheOwnerRole() throws Exception {
    assertThatThrownBy(
            () -> {
              try (Statement statement = appConnection.createStatement()) {
                statement.execute("SET ROLE " + OWNER_ROLE);
              }
            })
        .isInstanceOf(SQLException.class);
  }

  /**
   * PostgreSQL 16 automatically grants a {@code CREATEROLE} role {@code ADMIN OPTION} on a role it
   * creates the moment {@code CREATE ROLE} runs - a real {@code pg_auth_members} row, attributed to
   * the database's bootstrap identity as grantor rather than to {@code audit_app_role} itself, and
   * (empirically, against real Postgres 18) not removable by a plain {@code REVOKE opaa_audit_owner
   * FROM audit_app_role} issued by {@code audit_app_role}: that statement only revokes grants it
   * made itself, and this one was not one of them.
   *
   * <p><b>This residual is an open escalation path, not a harmless one - a first pass at this test
   * wrongly concluded otherwise</b> (PR #428, first re-review round), by checking only that {@code
   * SET ROLE} itself is blocked and that the residual grant's own {@code inherit}/{@code set}
   * columns are both {@code false} (see {@link
   * #theApplicationAccountCannotSwitchItsSessionIdentityToTheOwnerRole()} above). What that missed:
   * {@code ADMIN OPTION} lets {@code audit_app_role} grant the membership <em>to itself again</em>,
   * this time explicitly {@code WITH SET TRUE} - two statements, no prior {@code SET ROLE} needed,
   * because the freshly issued grant is inherited immediately. This test reproduces that escalation
   * and asserts it currently <em>succeeds</em> - the red half of the reproduction AGENTS.md
   * requires: it documents a real, currently open gap in today's bootstrap model (single account
   * for migration and runtime), not a passing security guarantee. See ADR-0015 and
   * 017-restrict-audit-log-privileges's changeSet comment for why the migration itself cannot close
   * this (the automatic grant's grantor is the database's bootstrap identity, not {@code
   * audit_app_role}, so {@code audit_app_role} has no standing to revoke it), and {@link
   * #theEscalationFailsWhenTheOwnerRoleIsProvisionedByASeparateIdentity()} below for the green
   * half: the same attack fails once {@code opaa_audit_owner} is created by an identity other than
   * the application account - the fix #426 tracks as an acceptance criterion, not solved in this
   * PR.
   */
  @Test
  void theApplicationAccountCanEscalateToOwnerInTodaysBootstrapModel() throws Exception {
    UUID eventId = insertMinimalEntry();

    try (Statement statement = appConnection.createStatement()) {
      statement.execute("GRANT " + OWNER_ROLE + " TO " + AUDIT_APP_ROLE + " WITH SET TRUE");
      statement.execute("DELETE FROM audit_log WHERE event_id = '" + eventId + "'");
    }

    try (Statement statement = bootstrapConnection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM audit_log WHERE event_id = '" + eventId + "'")) {
      result.next();
      assertThat(result.getInt(1))
          .as(
              "known, tracked escalation (#426): GRANT ... WITH SET TRUE followed by DELETE"
                  + " currently succeeds for the application account in the single-account"
                  + " bootstrap model")
          .isZero();
    }
  }

  /**
   * The fix #426 tracks: {@code opaa_audit_owner}'s real-world counterpart, {@code
   * opaa_audit_owner_demo}, is created and owns a table here entirely through {@code
   * bootstrapConnection} - a separate identity {@code audit_app_role} never becomes {@code
   * CREATEROLE}-equivalent to. {@code audit_app_role} only ever receives the same restricted
   * INSERT/SELECT grant 017-restrict-audit-log-privileges grants on the real {@code audit_log}, set
   * up externally rather than by {@code audit_app_role} itself running the ownership-transfer
   * choreography. Under that model, {@code audit_app_role} has no {@code pg_auth_members} row for
   * {@code opaa_audit_owner_demo} at all - not even the automatic {@code ADMIN OPTION} residual -
   * so the same two-statement escalation {@link
   * #theApplicationAccountCanEscalateToOwnerInTodaysBootstrapModel()} demonstrates against the real
   * table fails here at the first statement.
   */
  @Test
  void theEscalationFailsWhenTheOwnerRoleIsProvisionedByASeparateIdentity() throws Exception {
    try (Statement statement = bootstrapConnection.createStatement()) {
      statement.execute("CREATE ROLE " + DEMO_OWNER_ROLE + " NOLOGIN");
      statement.execute("CREATE TABLE " + DEMO_TABLE + " (id uuid PRIMARY KEY)");
      statement.execute("ALTER TABLE " + DEMO_TABLE + " OWNER TO " + DEMO_OWNER_ROLE);
      statement.execute("GRANT INSERT, SELECT ON " + DEMO_TABLE + " TO " + AUDIT_APP_ROLE);
    }

    assertThatThrownBy(
            () -> {
              try (Statement statement = appConnection.createStatement()) {
                statement.execute(
                    "GRANT " + DEMO_OWNER_ROLE + " TO " + AUDIT_APP_ROLE + " WITH SET TRUE");
              }
            })
        .isInstanceOf(SQLException.class);

    UUID demoId = UUID.randomUUID();
    try (Statement statement = appConnection.createStatement()) {
      statement.execute("INSERT INTO " + DEMO_TABLE + " (id) VALUES ('" + demoId + "')");
    }
    assertThatThrownBy(
            () -> {
              try (Statement statement = appConnection.createStatement()) {
                statement.execute("DELETE FROM " + DEMO_TABLE + " WHERE id = '" + demoId + "'");
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

  // --- partitioning horizon (review finding 3: no DEFAULT partition; a long, fixed horizon
  // instead) ---

  @Test
  void theTableIsPartitionedByMonthWithALongFixedHorizonAndNoDefaultPartition() throws Exception {
    assertThat(relKind("audit_log")).isEqualTo("p");
    assertThat(partitionCount()).isGreaterThanOrEqualTo(190);
    assertThat(partitionExists("audit_log_default")).isFalse();
  }

  @Test
  void aWriteFifteenYearsInTheFutureIsAcceptedWithinTheHorizon() throws Exception {
    Instant fifteenYearsOut = Instant.now().plus(15 * 365, ChronoUnit.DAYS);

    UUID eventId = insertMinimalEntryAt(fifteenYearsOut);

    try (Statement statement = appConnection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM audit_log WHERE event_id = '" + eventId + "'")) {
      result.next();
      assertThat(result.getInt(1)).isEqualTo(1);
    }
  }

  @Test
  void aWriteTwentyYearsInTheFutureFailsHardInsteadOfLandingInAnUnreclaimablePartition()
      throws Exception {
    Instant twentyYearsOut = Instant.now().plus(20 * 365, ChronoUnit.DAYS);

    assertThatThrownBy(() -> insertMinimalEntryAt(twentyYearsOut))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("no partition of relation");
  }

  // --- closed-list check constraints match the Java enums exactly (all five, not only two) ---

  @Test
  void theActorKindCheckConstraintMatchesTheJavaEnumExactly() throws Exception {
    assertThat(checkConstraintValues("chk_audit_log_actor_kind"))
        .isEqualTo(enumNames(ActorKind.values()));
  }

  @Test
  void theOutcomeCheckConstraintMatchesTheJavaEnumExactly() throws Exception {
    assertThat(checkConstraintValues("chk_audit_log_outcome"))
        .isEqualTo(enumNames(AuditOutcome.values()));
  }

  @Test
  void theSubjectKindCheckConstraintMatchesTheJavaEnumExactly() throws Exception {
    assertThat(checkConstraintValues("chk_audit_log_subject"))
        .isEqualTo(enumNames(AuditSubjectKind.values()));
  }

  @Test
  void theEventTypeCheckConstraintMatchesTheJavaEnumExactly() throws Exception {
    // #393 code review, finding 1: migration 022 widens this same constraint to also accept
    // AUDITOR_ROLE_GRANTED/AUDITOR_ROLE_REVOKED, #545's migration 035 further widens it to accept
    // LIBRARY_SOURCE_UPDATED, and #543's migration 040 further widens it to accept SPACE_ARCHIVED
    // - this test applies 017 alone (on top of test-master-through-016), so it must compare
    // against 017's own, narrower value set, not the full live enum, which now includes those
    // later-added values. See Migration022AuditorRoleEventTypesTest/
    // Migration035LibrarySourceUpdatedEventTypeTest/Migration040SpaceArchivedEventTypeTest for the
    // equivalent proof once 022/035/040 have run.
    Set<String> valuesAddedAfterMigration017 =
        Set.of(
            AuditEventType.AUDITOR_ROLE_GRANTED.name(),
            AuditEventType.AUDITOR_ROLE_REVOKED.name(),
            AuditEventType.LIBRARY_SOURCE_UPDATED.name(),
            AuditEventType.SPACE_ARCHIVED.name());
    Set<String> expected = new HashSet<>(enumNames(AuditEventType.values()));
    expected.removeAll(valuesAddedAfterMigration017);

    assertThat(checkConstraintValues("chk_audit_log_event_type")).isEqualTo(expected);
  }

  @Test
  void theObjectTypeCheckConstraintMatchesTheJavaEnumExactly() throws Exception {
    assertThat(checkConstraintValues("chk_audit_log_object_type"))
        .isEqualTo(enumNames(AuditObjectType.values()));
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
                    "INSERT INTO audit_log (event_id, recorded_at, organization_id, actor_kind,"
                        + " actor_ref, event_type, object_type, object_id, subject_ref, outcome)"
                        + " VALUES ('"
                        + eventId
                        + "', now(), '"
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

  private Set<String> enumNames(Enum<?>[] values) {
    Set<String> names = new HashSet<>();
    for (Enum<?> value : values) {
      names.add(value.name());
    }
    return names;
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
    return insertEntry(UUID.randomUUID(), Instant.now(), actorRef, eventType);
  }

  private UUID insertMinimalEntryAt(Instant recordedAt) throws SQLException {
    return insertEntry(UUID.randomUUID(), recordedAt, "pseud-actor-1", "SPACE_CREATED");
  }

  private UUID insertEntry(UUID eventId, Instant recordedAt, String actorRef, String eventType)
      throws SQLException {
    try (Statement statement = appConnection.createStatement()) {
      statement.execute(
          "INSERT INTO audit_log (event_id, recorded_at, organization_id, actor_kind, actor_ref,"
              + " event_type, object_type, object_id, outcome) VALUES ('"
              + eventId
              + "', '"
              + recordedAt
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

  /** The physical partition table an already-written entry actually landed in, via tableoid. */
  private String partitionNameOf(UUID eventId) throws SQLException {
    try (Statement statement = bootstrapConnection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT tableoid::regclass::text FROM audit_log WHERE event_id = '"
                    + eventId
                    + "'")) {
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
