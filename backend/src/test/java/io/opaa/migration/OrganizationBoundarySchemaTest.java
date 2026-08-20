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
 * #390: a structural, schema-wide proof that the organization boundary holds at the database level
 * - not a behavioural test of any one migration (those live in {@code Migration046*}, {@code
 * Migration047*}, {@code Migration048*} and {@code Migration049*}), but a permanent check that
 * closes the *class* of defect #289 was one instance of: a table carrying {@code organization_id}
 * whose foreign key to another {@code organization_id}-carrying table is a plain, single-column key
 * instead of the composite {@code (fk_column, organization_id) -> (referenced_pk, organization_id)}
 * shape the rest of the schema relies on.
 *
 * <p>Unlike every other test in this package, this one applies the <b>full</b> master changelog
 * ({@link #baseFixtureChangelogPath()} returns {@code db/changelog/db.changelog-master.yaml}, not a
 * {@code test-master-through-XXX.yaml} fixture) and reads the resulting schema directly from
 * PostgreSQL's own catalogs ({@code information_schema.columns}, {@code pg_constraint}, {@code
 * pg_attribute}) - never the changelog YAML files themselves. What counts is the database state
 * after every migration has run, not what a YAML file claims it does.
 *
 * <p>Both the set of tables that must be checked (those carrying {@code organization_id}) and the
 * set of targets the rule applies to (the same set - a table is "organization-scoped" as a base
 * table and as a referenced table alike) are determined at runtime via {@link
 * #tablesWithOrganizationId(Connection)}, never hand-maintained here - see {@link
 * #findViolations(Connection)}.
 *
 * <p><b>Structural limits of this check (documented, not exceptions - see the maintainer's comment
 * on #390, 20.08.2026):</b>
 *
 * <ul>
 *   <li>The history tables from migration 018 ({@code asset_grant_history.library_id}/{@code
 *       subject_group_id}, {@code group_membership_history.group_id}, {@code
 *       library_visibility_history}'s subject columns) deliberately carry no foreign key at all -
 *       see that migration's own "Deletion survival" comment. A foreign-key-based check such as
 *       this one cannot see a column that was never a foreign key in the first place; those columns
 *       are out of scope by construction, not covered by the rule.
 *   <li>{@code vector_store} (the Spring AI vector store table) records the organization only
 *       inside a JSON metadata blob, not as a relational column - {@link #tablesWithOrganizationId}
 *       only finds real {@code organization_id} columns, so this table never enters either set this
 *       check builds.
 * </ul>
 *
 * <p><b>Exception list:</b> {@link #DOCUMENTED_EXCEPTIONS} is empty. Migrations 046-049 (#400,
 * #289, #677, #401) closed every violation the original #390 analysis (and the maintainer's updated
 * inventory of 20.08.2026) found; nothing here needs a carve-out. Should a future migration
 * legitimately need one, add an entry with a mandatory justification and the issue it was created
 * under - see {@link BoundaryException} - and note that {@link
 * #everyOrganizationScopedForeignKeyIsComposite()} also fails if a listed exception no longer
 * describes an actual violation, so stale entries cannot linger unnoticed.
 */
class OrganizationBoundarySchemaTest extends AbstractMigrationTest {

  /**
   * Empty on purpose (see this class's Javadoc). A future entry must carry table, constraint name,
   * a mandatory justification, and the issue under which it was created.
   */
  private static final List<BoundaryException> DOCUMENTED_EXCEPTIONS = List.of();

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/db.changelog-master.yaml";
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
  void everyOrganizationScopedForeignKeyIsComposite() throws SQLException {
    List<Violation> violations = findViolations(connection);

    List<String> staleExceptions =
        DOCUMENTED_EXCEPTIONS.stream()
            .filter(exception -> violations.stream().noneMatch(exception::matches))
            .map(BoundaryException::describe)
            .toList();
    assertThat(staleExceptions)
        .as(
            "Documented exceptions that no longer describe an actual violation - remove them from"
                + " DOCUMENTED_EXCEPTIONS, the database no longer needs them:\n"
                + String.join("\n", staleExceptions))
        .isEmpty();

    List<Violation> undocumented =
        violations.stream()
            .filter(
                violation ->
                    DOCUMENTED_EXCEPTIONS.stream()
                        .noneMatch(exception -> exception.matches(violation)))
            .toList();
    assertThat(undocumented)
        .as(
            "Organization boundary violation(s) found. Every foreign key from a table that carries"
                + " organization_id to another table that also carries organization_id must be"
                + " composite - (fk_column, organization_id) -> (referenced_pk, organization_id) -"
                + " not a plain single-column key. Violations:\n"
                + violations.stream().map(Violation::describe).collect(Collectors.joining("\n")))
        .isEmpty();
  }

  /**
   * Sonderfall {@code users} (see #390's issue body): {@code users} itself carries {@code
   * organization_id}, so every table referencing it falls under the rule above - this asserts that
   * remains true today, i.e. that {@link #everyOrganizationScopedForeignKeyIsComposite()} is not
   * vacuously green because nothing references {@code users} at all.
   */
  @Test
  void usersIsPartOfTheOrganizationScopedTargetSetAndIsActuallyReferenced() throws SQLException {
    Set<String> organizationScopedTables = tablesWithOrganizationId(connection);
    assertThat(organizationScopedTables).contains("users");

    List<ForeignKeyRow> foreignKeysToUsers =
        foreignKeys(connection).stream()
            .filter(fk -> "users".equals(fk.referencedTable()))
            .toList();
    assertThat(foreignKeysToUsers)
        .as("at least one organization-scoped table must reference users(id, organization_id)")
        .isNotEmpty();
  }

  /**
   * Permanent negative test (#390 review requirement): proves the check itself catches a single-
   * column foreign key between two organization-scoped tables, in a pair of tables created here for
   * exactly this purpose - not by relying on today's schema happening to contain a violation (it
   * does not, see {@link #everyOrganizationScopedForeignKeyIsComposite()}).
   */
  @Test
  void aSingleColumnForeignKeyBetweenTwoOrganizationScopedTablesIsDetectedAsAViolation()
      throws SQLException {
    createArtificialOrganizationScopedTablesWithASingleColumnForeignKey();

    List<Violation> violations = findViolations(connection);

    assertThat(violations)
        .as(
            "the rest of the schema is already clean (see"
                + " everyOrganizationScopedForeignKeyIsComposite) - the only violation must be the"
                + " artificial one this test just created")
        .hasSize(1);
    Violation violation = violations.get(0);
    assertThat(violation.table()).isEqualTo("test_boundary_child");
    assertThat(violation.constraintName()).isEqualTo("fk_test_boundary_child_parent");
    assertThat(violation.referencedTable()).isEqualTo("test_boundary_parent");
    assertThat(violation.baseColumns()).containsExactly("parent_id");
    assertThat(violation.referencedColumns()).containsExactly("id");
  }

  private void createArtificialOrganizationScopedTablesWithASingleColumnForeignKey()
      throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE test_boundary_parent (id uuid PRIMARY KEY, organization_id uuid NOT NULL"
              + " REFERENCES organizations(id))");
      statement.execute(
          "CREATE TABLE test_boundary_child (id uuid PRIMARY KEY, organization_id uuid NOT NULL"
              + " REFERENCES organizations(id), parent_id uuid NOT NULL,"
              + " CONSTRAINT fk_test_boundary_child_parent FOREIGN KEY (parent_id) REFERENCES"
              + " test_boundary_parent(id))");
    }
  }

  /**
   * The rule itself: every foreign key whose base table and referenced table both carry {@code
   * organization_id} must include {@code organization_id} in its own column list, matched with
   * {@code organization_id} on the referenced side at the same position. Collects every violation
   * instead of stopping at the first, so a migration author sees the complete list in one run.
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
          resolvedColumns(connection, foreignKey.constraintName(), "conkey", "conrelid");
      List<String> referencedColumns =
          resolvedColumns(connection, foreignKey.constraintName(), "confkey", "confrelid");
      int organizationIdIndex = baseColumns.indexOf("organization_id");
      boolean isComposite =
          organizationIdIndex >= 0
              && organizationIdIndex < referencedColumns.size()
              && "organization_id".equals(referencedColumns.get(organizationIdIndex));
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
   * Every base table in {@code public} that has a (non-dropped) {@code organization_id} column -
   * both the set of tables this check must examine and the set of targets the composite-key rule
   * applies to, determined from the schema itself, never hand-maintained (#390 issue body).
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
   * parent (e.g. {@code audit_log}, migration 017) - without it, one constraint on a partitioned
   * table would appear once per partition here.
   */
  private List<ForeignKeyRow> foreignKeys(Connection connection) throws SQLException {
    List<ForeignKeyRow> foreignKeys = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT c.conname, bc.relname AS base_table, rc.relname AS referenced_table"
                    + " FROM pg_constraint c"
                    + " JOIN pg_class bc ON bc.oid = c.conrelid"
                    + " JOIN pg_namespace bn ON bn.oid = bc.relnamespace"
                    + " JOIN pg_class rc ON rc.oid = c.confrelid"
                    + " WHERE c.contype = 'f' AND bn.nspname = 'public' AND c.conparentid = 0"
                    + " ORDER BY c.conname")) {
      while (result.next()) {
        foreignKeys.add(
            new ForeignKeyRow(
                result.getString("conname"),
                result.getString("base_table"),
                result.getString("referenced_table")));
      }
    }
    return foreignKeys;
  }

  /** Resolves an {@code int2vector}/{@code smallint[]} attribute-number column to column names. */
  private List<String> resolvedColumns(
      Connection connection, String constraintName, String keyColumn, String relIdColumn)
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
                + " WHERE c.conname = ? AND c.conparentid = 0 ORDER BY k.ord")) {
      statement.setString(1, constraintName);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          columns.add(result.getString("attname"));
        }
      }
    }
    return columns;
  }

  /** One foreign key constraint, as read from {@code pg_constraint} - not yet checked. */
  private record ForeignKeyRow(String constraintName, String baseTable, String referencedTable) {}

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

  /**
   * One documented, justified exception to the composite-key rule. Every field is mandatory: a
   * carve-out without a stated reason and a traceable issue is exactly the silent erosion #390's
   * issue body warns against.
   */
  private record BoundaryException(
      String table, String constraintName, String justification, String issue) {

    boolean matches(Violation violation) {
      return table.equals(violation.table()) && constraintName.equals(violation.constraintName());
    }

    String describe() {
      return "table="
          + table
          + " constraint="
          + constraintName
          + " issue="
          + issue
          + " justification="
          + justification;
    }
  }
}
