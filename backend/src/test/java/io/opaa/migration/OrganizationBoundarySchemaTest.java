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

    List<String> staleExceptions = staleExceptionDescriptions(violations, DOCUMENTED_EXCEPTIONS);
    assertThat(staleExceptions)
        .as(
            "Documented exceptions that no longer describe an actual violation - remove them from"
                + " DOCUMENTED_EXCEPTIONS, the database no longer needs them:\n"
                + String.join("\n", staleExceptions))
        .isEmpty();

    List<Violation> undocumented = undocumentedViolations(violations, DOCUMENTED_EXCEPTIONS);
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
   * #390 review, Befund 4: {@code organizations} (migration 008 - {@code id}, {@code name}, {@code
   * created_at}) is the tenant root and carries no {@code organization_id} column of its own, so it
   * never enters {@link #tablesWithOrganizationId(Connection)} and a plain single-column {@code
   * fk_*_organization} onto it is correctly outside the composite-key rule's scope. That followed
   * only implicitly from the column being absent; this test makes it an explicit, checked fact
   * instead, the same way {@link
   * #usersIsPartOfTheOrganizationScopedTargetSetAndIsActuallyReferenced()} makes the {@code users}
   * side of the target set explicit.
   */
  @Test
  void organizationsIsNeverPartOfTheOrganizationScopedTargetSet() throws SQLException {
    Set<String> organizationScopedTables = tablesWithOrganizationId(connection);

    assertThat(organizationScopedTables)
        .as("organizations is the tenant root and carries no organization_id column of its own")
        .doesNotContain("organizations");
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

  /**
   * #390 review, Befund 2: {@link #DOCUMENTED_EXCEPTIONS} is empty in production, so without this
   * test neither {@link BoundaryException#matches(Violation)} nor the staleness check in {@link
   * #everyOrganizationScopedForeignKeyIsComposite()} would ever actually run against a real
   * violation - an assertion that is never exercised is exactly the kind of untested "fix" {@code
   * AGENTS.md}'s Reproduktionsnachweis section warns against. Reuses the same artificial violation
   * as {@link #aSingleColumnForeignKeyBetweenTwoOrganizationScopedTablesIsDetectedAsAViolation()}:
   * a documented exception that names it must remove it from {@link #undocumentedViolations(List,
   * List)} and must not appear in {@link #staleExceptionDescriptions(List, List)}.
   */
  @Test
  void aDocumentedExceptionCoversTheMatchingViolationAndIsNotStale() throws SQLException {
    createArtificialOrganizationScopedTablesWithASingleColumnForeignKey();
    List<Violation> violations = findViolations(connection);
    List<BoundaryException> matchingException =
        List.of(
            new BoundaryException(
                "test_boundary_child",
                "fk_test_boundary_child_parent",
                "artificial violation created by this test, not a real exception",
                "#390"));

    assertThat(undocumentedViolations(violations, matchingException))
        .as("a documented exception naming exactly this violation must suppress it")
        .isEmpty();
    assertThat(staleExceptionDescriptions(violations, matchingException))
        .as("the exception still matches an actual violation, so it must not be reported as stale")
        .isEmpty();
  }

  /**
   * The complement of {@link #aDocumentedExceptionCoversTheMatchingViolationAndIsNotStale()}: an
   * exception that names a constraint no violation currently has must be reported as stale, not
   * silently accepted - see this class's Javadoc, "Exception list".
   */
  @Test
  void aDocumentedExceptionThatMatchesNoViolationIsReportedAsStale() throws SQLException {
    List<Violation> violations = findViolations(connection);
    List<BoundaryException> staleException =
        List.of(
            new BoundaryException(
                "space_memberships",
                "fk_space_memberships_space",
                "no longer needed - migration 050 removed the redundant constraint this exception"
                    + " once covered",
                "#390"));

    assertThat(staleExceptionDescriptions(violations, staleException))
        .as(
            "an exception naming a constraint that is not among today's violations must be flagged"
                + " as stale, since today's schema (after migration 050) no longer has this"
                + " violation")
        .hasSize(1);
  }

  /**
   * #390 review, Befund 5: a foreign key whose only base column is {@code organization_id} itself -
   * (organization_id) -> (organization_id) - would satisfy the naive "organization_id is present at
   * a matching index" check without actually binding any real, object-identifying column. {@link
   * #findViolations(Connection)} requires at least one non-organization_id column alongside it, so
   * this degenerate shape must still be reported as a violation, not accepted as composite.
   */
  @Test
  void aForeignKeyThatIsOnlyOrganizationIdIsNotAcceptedAsComposite() throws SQLException {
    createArtificialOrganizationScopedTablesWithADegenerateOrganizationIdOnlyForeignKey();

    List<Violation> violations = findViolations(connection);

    assertThat(violations)
        .as("the only violation must be the artificial degenerate one this test just created")
        .hasSize(1);
    Violation violation = violations.get(0);
    assertThat(violation.table()).isEqualTo("test_org_only_child");
    assertThat(violation.constraintName()).isEqualTo("fk_test_org_only_child_degenerate");
    assertThat(violation.baseColumns()).containsExactly("organization_id");
    assertThat(violation.referencedColumns()).containsExactly("organization_id");
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
   * A parent/child pair where the child's only foreign key column *is* {@code organization_id},
   * referencing the parent's own {@code organization_id} (which needs its own unique constraint to
   * be a valid FK target) - the degenerate case {@link
   * #aForeignKeyThatIsOnlyOrganizationIdIsNotAcceptedAsComposite()} proves is still rejected.
   */
  private void createArtificialOrganizationScopedTablesWithADegenerateOrganizationIdOnlyForeignKey()
      throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE test_org_only_parent (id uuid PRIMARY KEY, organization_id uuid NOT NULL"
              + " REFERENCES organizations(id),"
              + " CONSTRAINT uk_test_org_only_parent_organization UNIQUE (organization_id))");
      statement.execute(
          "CREATE TABLE test_org_only_child (id uuid PRIMARY KEY, organization_id uuid NOT NULL,"
              + " CONSTRAINT fk_test_org_only_child_degenerate FOREIGN KEY (organization_id)"
              + " REFERENCES test_org_only_parent(organization_id))");
    }
  }

  /**
   * The undocumented subset of {@code violations}: those with no matching entry in {@code
   * exceptions}. A pure, static function of both lists (#390 review, Befund 2) so both {@link
   * #everyOrganizationScopedForeignKeyIsComposite()} and the exception-mechanics tests exercise the
   * exact same logic.
   */
  private static List<Violation> undocumentedViolations(
      List<Violation> violations, List<BoundaryException> exceptions) {
    return violations.stream()
        .filter(
            violation -> exceptions.stream().noneMatch(exception -> exception.matches(violation)))
        .toList();
  }

  /**
   * The subset of {@code exceptions} that no longer matches any entry in {@code violations} - see
   * {@link #undocumentedViolations(List, List)}.
   */
  private static List<String> staleExceptionDescriptions(
      List<Violation> violations, List<BoundaryException> exceptions) {
    return exceptions.stream()
        .filter(exception -> violations.stream().noneMatch(exception::matches))
        .map(BoundaryException::describe)
        .toList();
  }

  /**
   * The rule itself: every foreign key whose base table and referenced table both carry {@code
   * organization_id} must include {@code organization_id} in its own column list, matched with
   * {@code organization_id} on the referenced side at the same position, AND carry at least one
   * further column besides {@code organization_id} (#390 review, Befund 5) - a degenerate
   * single-column {@code (organization_id) -> (organization_id)} foreign key would satisfy the
   * index check without binding any actual object, so it must not count as composite. Collects
   * every violation instead of stopping at the first, so a migration author sees the complete list
   * in one run.
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
   * Every base table in {@code public} that has a (non-dropped) {@code organization_id} column -
   * both the set of tables this check must examine and the set of targets the composite-key rule
   * applies to, determined from the schema itself, never hand-maintained (#390 issue body).
   *
   * <p>{@code organizations} itself (the tenant root, migration 008) is never part of this set: it
   * carries no {@code organization_id} column of its own, so it never satisfies the {@code
   * c.column_name = 'organization_id'} predicate below - a plain single-column {@code
   * fk_*_organization} onto {@code organizations} is therefore correctly out of scope for the
   * composite-key rule (#390 review, Befund 4). {@code <> 'organizations'} makes that explicit
   * rather than relying solely on the column being absent; see {@link
   * #usersIsPartOfTheOrganizationScopedTargetSetAndIsActuallyReferenced()} for the analogous
   * explicit assertion on the {@code users} side of the target set.
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
   * parent (e.g. {@code audit_log}, migration 017) - without it, one constraint on a partitioned
   * table would appear once per partition here. {@code c.oid} is carried through {@link
   * ForeignKeyRow} and used by {@link #resolvedColumns(Connection, long, String, String)} instead
   * of the constraint name (#390 review, Befund 1): a constraint name is only unique per table in
   * Postgres, not per schema, so looking columns up by name alone could silently interleave the
   * columns of two same-named constraints on different tables.
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
   * only unique per table (#390 review, Befund 1).
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
