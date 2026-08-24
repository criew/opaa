package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies Liquibase changelog 063 in isolation, on top of 017 - the same restricted-role pattern
 * {@code Migration022AuditorRoleEventTypesTest} establishes (see its own Javadoc for the full
 * reasoning): {@code audit_log} is owned by {@code opaa_audit_owner} after 017, not by the
 * migration/application account, so this must run as a non-superuser {@code AUDIT_APP_ROLE} - a
 * real Postgres superuser bypasses every ownership check, which would leave 063's own {@code SET
 * ROLE opaa_audit_owner} step untested.
 *
 * <p>Proves #834's acceptance criteria against a real database: both {@code
 * idx_audit_log_time_range} and {@code idx_audit_log_incident_scope} exist after 063 runs, are
 * defined with the column order {@link io.opaa.audit.AuditQueryService#byTimeRange}/{@link
 * io.opaa.audit.AuditQueryService#byIncidentScope} actually query by, and - since {@code audit_log}
 * is partitioned by month (migration 017) - are propagated onto an existing partition as well, not
 * only visible on the parent relation.
 */
class Migration063AuditLogTimeRangeAndIncidentScopeIndexesTest extends AbstractMigrationTest {

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";
  private static final String AUDIT_APP_ROLE = "audit_app_role";
  private static final String AUDIT_APP_ROLE_PASSWORD = "audit_app_role_password";
  private static final String OWNER_ROLE = "opaa_audit_owner";

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
    applyChangelog(
        appConnection,
        "db/changelog/changes/063-audit-log-time-range-and-incident-scope-indexes.yaml");
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
    // class shares with the other audit_log migration test classes against the same singleton
    // container - see AbstractMigrationTest#dropRolesIfExist(...).
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

  @Test
  void theTimeRangeIndexExistsOnTheParentTableWithTheExpectedColumns() throws Exception {
    assertThat(indexColumns("audit_log", "idx_audit_log_time_range"))
        .containsExactly("organization_id", "recorded_at");
  }

  @Test
  void theIncidentScopeIndexExistsOnTheParentTableWithTheExpectedColumns() throws Exception {
    assertThat(indexColumns("audit_log", "idx_audit_log_incident_scope"))
        .containsExactly("organization_id", "actor_ref", "recorded_at");
  }

  @Test
  void bothIndexesArePropagatedOntoAnExistingPartitionNotOnlyTheParent() throws Exception {
    String partition = anyPartitionName();

    // A partition's own propagated index is not named after the parent's index
    // (idx_audit_log_time_range) - Postgres derives a fresh, autogenerated name per partition from
    // the partition's own table/column names (verified empirically:
    // "audit_log_2026_05_organization_id_recorded_at_idx") - so this asserts by column composition
    // instead of by name, the same way indexColumns() already does for the parent.
    assertThat(anyIndexHasColumns(partition, "organization_id", "recorded_at")).isTrue();
    assertThat(anyIndexHasColumns(partition, "organization_id", "actor_ref", "recorded_at"))
        .isTrue();
  }

  @Test
  void theApplicationAccountCanStillOnlyInsertAndSelectAfter063() throws Exception {
    // The temporary SET TRUE / SET ROLE bracket 063 uses to reach CREATE INDEX must not leave the
    // application account with any lingering elevated privilege afterwards - the same guarantee
    // Migration022AuditorRoleEventTypesTest proves for its own DDL.
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

  private UUID insertMinimalEntry() throws SQLException {
    UUID eventId = UUID.randomUUID();
    try (Statement statement = appConnection.createStatement()) {
      statement.execute(
          "INSERT INTO audit_log (event_id, recorded_at, organization_id, actor_kind, actor_ref,"
              + " event_type, object_type, object_id, outcome) VALUES ('"
              + eventId
              + "', now(), '"
              + SEEDED_ORGANIZATION_ID
              + "', 'USER', 'pseud-actor-1', 'SPACE_CREATED', 'SPACE', 'space-1', 'SUCCESS')");
    }
    return eventId;
  }

  private String anyPartitionName() throws SQLException {
    try (Statement statement = bootstrapConnection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT inhrelid::regclass::text FROM pg_inherits"
                    + " JOIN pg_class parent ON pg_inherits.inhparent = parent.oid"
                    + " WHERE parent.relname = 'audit_log' LIMIT 1")) {
      result.next();
      return result.getString(1);
    }
  }

  /** Column names of the named index on the named relation, in index-definition order. */
  private List<String> indexColumns(String relationName, String indexName) throws SQLException {
    try (Statement statement = bootstrapConnection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT a.attname FROM pg_index i"
                    + " JOIN pg_class t ON t.oid = i.indrelid"
                    + " JOIN pg_class ix ON ix.oid = i.indexrelid"
                    + " JOIN unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord) ON true"
                    + " JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum"
                    + " WHERE t.relname = '"
                    + relationName
                    + "' AND ix.relname = '"
                    + indexName
                    + "'"
                    + " ORDER BY k.ord")) {
      List<String> columns = new ArrayList<>();
      while (result.next()) {
        columns.add(result.getString(1));
      }
      return columns;
    }
  }

  /**
   * Whether the given relation carries any index whose column list, in order, exactly matches
   * {@code expectedColumns} - used for the partition-level check, where the propagated index's own
   * name is autogenerated and cannot be predicted (see the caller's comment).
   */
  private boolean anyIndexHasColumns(String relationName, String... expectedColumns)
      throws SQLException {
    List<String> expected = List.of(expectedColumns);
    String bareRelationName = relationName.replace("public.", "");
    Set<String> indexNames = new HashSet<>();
    try (Statement statement = bootstrapConnection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT indexname FROM pg_indexes WHERE tablename = '" + bareRelationName + "'")) {
      while (result.next()) {
        indexNames.add(result.getString(1));
      }
    }
    for (String indexName : indexNames) {
      if (indexColumns(bareRelationName, indexName).equals(expected)) {
        return true;
      }
    }
    return false;
  }
}
