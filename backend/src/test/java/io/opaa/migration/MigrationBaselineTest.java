package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The one remaining {@code io.opaa.migration} test after #904 consolidated 257 historical
 * changesets into a single baseline ({@code db/changelog/changes/001-baseline.yaml}): applies that
 * baseline to an empty database and asserts a handful of core invariants a broken baseline would
 * violate - representative tables and their pgvector/partition/ownership peculiarities exist, the
 * two seed rows are present, and the organization-boundary composite-foreign-key rule (formerly
 * {@code OrganizationBoundarySchemaTest}, #390) still holds schema-wide.
 *
 * <p>The 52 deleted classes each tested one historical transition (schema state N-1 to N); none of
 * those transitions exist anymore; the equivalence between the old 67-file chain and the baseline
 * is a one-time proof (see the #904 pull request description for the pg_dump diff), not an ongoing
 * regression guard - the same way old migrations themselves are never re-tested once superseded.
 * Future changesets get their own delta test under this package again, against a fixture chain that
 * now starts from {@code db/changelog/test-master-through-baseline.yaml} instead of one of the 18
 * deleted {@code test-master-through-0NN.yaml} files.
 */
class MigrationBaselineTest extends AbstractMigrationTest {

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/changes/001-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws SQLException {
    connection = connect();
    connection.setAutoCommit(true);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void createsEveryTableAcrossAllSchemaGroups() throws SQLException {
    // One representative table per baseline group (a-h) - not an exhaustive list, just enough to
    // catch a whole group silently missing from the baseline.
    List<String> representativeTables =
        List.of(
            "organizations",
            "users",
            "spaces",
            "groups",
            "knowledge_libraries",
            "documents",
            "chats",
            "llm_models",
            "audit_log",
            "asset_grant_history",
            "notifications",
            "branding_settings");
    for (String table : representativeTables) {
      assertThat(tableExists(table)).as("table %s must exist", table).isTrue();
    }
  }

  @Test
  void enablesPgvectorExtension() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery("SELECT 1 FROM pg_extension WHERE extname = 'vector'")) {
      assertThat(rs.next()).as("vector extension must be enabled").isTrue();
    }
  }

  @Test
  void seedsExactlyOneOrganizationAndOneBrandingSettingsRow() throws SQLException {
    assertThat(countRows("organizations")).isEqualTo(1);
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT id, name FROM organizations "
                    + "WHERE id = '00000000-0000-0000-0000-000000000001'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString("name")).isEqualTo("Default");
    }

    assertThat(countRows("branding_settings")).isEqualTo(1);
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT id FROM branding_settings WHERE id = 1")) {
      assertThat(rs.next()).isTrue();
    }
  }

  @Test
  void partitionsAuditLogByMonthAndOwnsItViaTheRestrictedRole() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT count(*) FROM pg_inherits WHERE inhparent = 'audit_log'::regclass")) {
      rs.next();
      // The horizon is 3 months back through 194 months forward (195 total) - see the baseline's
      // own comment on this DO block for the full rationale.
      assertThat(rs.getInt(1)).isEqualTo(195);
    }

    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT relowner::regrole::text FROM pg_class WHERE relname = 'audit_log'")) {
      rs.next();
      assertThat(rs.getString(1)).isEqualTo("opaa_audit_owner");
    }
  }

  /**
   * Ported from the former {@code OrganizationBoundarySchemaTest} (#390): a structural, schema-wide
   * proof that every foreign key between two tables that both carry {@code organization_id} is
   * composite - {@code (fk_column, organization_id) -> (referenced_pk, organization_id)} - not a
   * plain single-column key. See {@link #findViolations(Connection)} for the exact rule; both sets
   * it examines (organization-scoped tables and their foreign keys) are read from the database
   * catalogs at runtime, never hand-maintained here.
   */
  @Test
  void everyOrganizationScopedForeignKeyIsComposite() throws SQLException {
    List<Violation> violations = findViolations(connection);
    assertThat(violations)
        .as(
            "Organization boundary violation(s) found. Every foreign key from a table that carries"
                + " organization_id to another table that also carries organization_id must be"
                + " composite - (fk_column, organization_id) -> (referenced_pk, organization_id) -"
                + " not a plain single-column key. Violations:\n"
                + violations.stream().map(Violation::describe).collect(Collectors.joining("\n")))
        .isEmpty();
  }

  private boolean tableExists(String tableName) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name = ?")) {
      statement.setString(1, tableName);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next();
      }
    }
  }

  private long countRows(String table) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  /**
   * The rule itself: every foreign key whose base table and referenced table both carry {@code
   * organization_id} must include {@code organization_id} in its own column list, matched with
   * {@code organization_id} on the referenced side at the same position, and carry at least one
   * further column besides {@code organization_id} - a degenerate single-column {@code
   * (organization_id) -> (organization_id)} foreign key would satisfy the index check without
   * binding any actual object, so it must not count as composite.
   */
  private List<Violation> findViolations(Connection connection) throws SQLException {
    Set<String> organizationScopedTables = tablesWithOrganizationId(connection);
    List<Violation> violations = new ArrayList<>();
    for (ForeignKeyRow foreignKey : foreignKeys(connection)) {
      if (!organizationScopedTables.contains(foreignKey.baseTable())
          || !organizationScopedTables.contains(foreignKey.referencedTable())) {
        continue;
      }
      List<String> baseColumns =
          resolvedColumns(connection, foreignKey.oid(), "conkey", "conrelid");
      List<String> referencedColumns =
          resolvedColumns(connection, foreignKey.oid(), "confkey", "confrelid");
      int organizationIdIndex = baseColumns.indexOf("organization_id");
      boolean isComposite =
          organizationIdIndex >= 0
              && organizationIdIndex < referencedColumns.size()
              && "organization_id".equals(referencedColumns.get(organizationIdIndex))
              && baseColumns.size() >= 2;
      if (!isComposite) {
        violations.add(
            new Violation(
                foreignKey.baseTable(),
                foreignKey.constraintName(),
                foreignKey.referencedTable(),
                baseColumns,
                referencedColumns));
      }
    }
    return violations;
  }

  /**
   * Every base table in {@code public} that has a (non-dropped) {@code organization_id} column,
   * excluding {@code organizations} itself (the tenant root, which carries no such column of its
   * own and is therefore correctly out of scope for the composite-key rule).
   */
  private Set<String> tablesWithOrganizationId(Connection connection) throws SQLException {
    Set<String> tables = new LinkedHashSet<>();
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT c.table_name FROM information_schema.columns c"
                    + " JOIN information_schema.tables t"
                    + "   ON t.table_schema = c.table_schema AND t.table_name = c.table_name"
                    + " WHERE c.table_schema = 'public' AND c.column_name = 'organization_id'"
                    + "   AND t.table_type = 'BASE TABLE'"
                    + "   AND c.table_name <> 'organizations'"
                    + " ORDER BY c.table_name")) {
      while (result.next()) {
        tables.add(result.getString("table_name"));
      }
    }
    return tables;
  }

  /**
   * Every foreign key constraint in {@code public}, base and referenced table names resolved via
   * {@code pg_class}/{@code pg_namespace} rather than {@code ::regclass::text} (which can return a
   * schema-qualified name depending on {@code search_path}). {@code conparentid = 0} excludes the
   * per-partition clones Postgres creates for a foreign key declared on a partitioned table's
   * parent (audit_log) - without it, one constraint on a partitioned table would appear once per
   * partition here.
   */
  private List<ForeignKeyRow> foreignKeys(Connection connection) throws SQLException {
    List<ForeignKeyRow> foreignKeys = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT c.oid, c.conname, bc.relname AS base_table, rc.relname AS referenced_table"
                    + " FROM pg_constraint c"
                    + " JOIN pg_class bc ON bc.oid = c.conrelid"
                    + " JOIN pg_namespace bn ON bn.oid = bc.relnamespace"
                    + " JOIN pg_class rc ON rc.oid = c.confrelid"
                    + " WHERE c.contype = 'f' AND bn.nspname = 'public' AND c.conparentid = 0"
                    + " ORDER BY c.conname")) {
      while (result.next()) {
        foreignKeys.add(
            new ForeignKeyRow(
                result.getLong("oid"),
                result.getString("conname"),
                result.getString("base_table"),
                result.getString("referenced_table")));
      }
    }
    return foreignKeys;
  }

  /**
   * Resolves an {@code int2vector}/{@code smallint[]} attribute-number column to column names, for
   * one specific constraint identified by its {@code pg_constraint.oid} - not its name, which is
   * only unique per table.
   */
  private List<String> resolvedColumns(
      Connection connection, long constraintOid, String keyColumn, String relIdColumn)
      throws SQLException {
    List<String> columns = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT a.attname FROM pg_constraint c"
                + " JOIN unnest(c."
                + keyColumn
                + ") WITH ORDINALITY AS k(attnum, ord) ON true"
                + " JOIN pg_attribute a ON a.attrelid = c."
                + relIdColumn
                + " AND a.attnum = k.attnum"
                + " WHERE c.oid = ? ORDER BY k.ord")) {
      statement.setLong(1, constraintOid);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          columns.add(result.getString("attname"));
        }
      }
    }
    return columns;
  }

  /** One foreign key constraint, as read from {@code pg_constraint} - not yet checked. */
  private record ForeignKeyRow(
      long oid, String constraintName, String baseTable, String referencedTable) {}

  /** One foreign key that fails the organization-boundary composite-key rule. */
  private record Violation(
      String table,
      String constraintName,
      String referencedTable,
      List<String> baseColumns,
      List<String> referencedColumns) {

    String describe() {
      return "table="
          + table
          + " constraint="
          + constraintName
          + " referencedTable="
          + referencedTable
          + " actualColumns="
          + baseColumns
          + " actualReferencedColumns="
          + referencedColumns
          + " (missing organization_id in the composite key)";
    }
  }
}
