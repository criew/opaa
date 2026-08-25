package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * One of the two remaining {@code io.opaa.migration} tests after #904 consolidated 134 historical
 * changesets into a single baseline ({@code db/changelog/changes/001-baseline.yaml}): applies that
 * baseline to an empty database and asserts a handful of core invariants a broken baseline would
 * violate - representative tables and their pgvector/partition/ownership peculiarities exist, the
 * seed rows are present, the organization-boundary composite-foreign-key rule (formerly {@code
 * OrganizationBoundarySchemaTest}, #390, ported back in full - see that section below) still holds
 * schema-wide, and a handful of current-state uniqueness/cascade invariants the deleted per-
 * changeset tests also covered as a side effect of testing their own transition.
 *
 * <p>The audit privilege model (ownership, ACL restriction, partition-horizon enforcement) has its
 * own test, {@link AuditPrivilegeModelTest} - see that class's Javadoc for what it covers and what
 * it deliberately does not.
 *
 * <p>The 52 deleted classes each tested one historical transition (schema state N-1 to N); most of
 * those transitions have no analogue in the baseline's current, consolidated state; the equivalence
 * between the old 67-file chain and the baseline is a one-time proof (see the #904 pull request
 * description for the pg_dump diff), not an ongoing regression guard - the same way old migrations
 * themselves are never re-tested once superseded. A handful of the deleted classes' assertions
 * tested current-state behaviour rather than a transition and are ported below instead; the #904
 * pull request description lists exactly which methods were and were not ported, and why. Future
 * changesets get their own delta test under this package again, against a fixture chain that now
 * starts from {@code db/changelog/test-master-through-baseline.yaml} instead of one of the 18
 * deleted {@code test-master-through-0NN.yaml} files.
 */
class MigrationBaselineTest extends AbstractMigrationTest {

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";

  /**
   * Empty on purpose (ported from {@code OrganizationBoundarySchemaTest}, #390 review): a future
   * entry must carry table, constraint name, a mandatory justification, and the issue it was
   * created under - see {@link BoundaryException}. {@link
   * #everyOrganizationScopedForeignKeyIsComposite()} also fails if a listed exception no longer
   * describes an actual violation, so stale entries cannot linger unnoticed.
   */
  private static final List<BoundaryException> DOCUMENTED_EXCEPTIONS = List.of();

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
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

  // ---------------------------------------------------------------------------------------------
  // Baseline smoke tests
  // ---------------------------------------------------------------------------------------------

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
  void seedsExactlyOneOrganizationOneBrandingSettingsRowAndOneAuditRetentionSettingsRow()
      throws SQLException {
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

    // The third seed row (baseline group (f), not (h) - see that group's own comment in
    // 001-baseline.yaml for why it cannot be deferred to group (h) with the other two).
    assertThat(countRows("audit_retention_settings")).isEqualTo(1);
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT retention_months FROM audit_retention_settings WHERE id = 1")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt("retention_months")).isEqualTo(36);
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

  // ---------------------------------------------------------------------------------------------
  // Current-state invariants ported from deleted per-changeset tests (#904 pull request
  // description lists what else those classes covered and why it was not ported)
  // ---------------------------------------------------------------------------------------------

  @Test
  void rejectsASecondConcurrentRunningIndexingJobForTheSameLibrary() throws SQLException {
    UUID libraryId = insertLibrary(insertUser());
    insertIndexingJob(libraryId, "RUNNING");

    assertThatThrownBy(() -> insertIndexingJob(libraryId, "RUNNING"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_indexing_jobs_library_running");
  }

  @Test
  void allowsRunningIndexingJobsForDifferentLibrariesAtTheSameTime() throws SQLException {
    UUID firstLibrary = insertLibrary(insertUser());
    UUID secondLibrary = insertLibrary(insertUser());

    insertIndexingJob(firstLibrary, "RUNNING");
    insertIndexingJob(secondLibrary, "RUNNING");

    assertThat(countRows("indexing_jobs")).isEqualTo(2);
  }

  @Test
  void rejectsASecondDocumentWithTheSamePathInTheSameLibrary() throws SQLException {
    UUID libraryId = insertLibrary(insertUser());
    insertDocument(libraryId, "/corpus/report.pdf");

    assertThatThrownBy(() -> insertDocument(libraryId, "/corpus/report.pdf"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_documents_library_path");
  }

  @Test
  void allowsTheSameDocumentPathInTwoDifferentLibraries() throws SQLException {
    // The exact case #877 fixed: two libraries indexing the same source path/URL must yield two
    // independent documents, not one library "stealing" the other's document.
    UUID firstLibrary = insertLibrary(insertUser());
    UUID secondLibrary = insertLibrary(insertUser());

    insertDocument(firstLibrary, "/corpus/report.pdf");
    insertDocument(secondLibrary, "/corpus/report.pdf");

    assertThat(countRows("documents")).isEqualTo(2);
  }

  @Test
  void rssFeedStateLetsTwoLibrariesEachHoldTheirOwnStateForTheSameFeedUrl() throws SQLException {
    // The exact fix #646 required: rss_feed_state is keyed by (library_id, feed_url), not feed_url
    // alone, so two libraries configured with the same feed address no longer collide.
    String feedUrl = "https://example.com/feed.xml";
    UUID libraryA = insertRssFeedLibrary(feedUrl);
    UUID libraryB = insertRssFeedLibrary(feedUrl);

    insertFeedState(libraryA, feedUrl, "\"etag-a\"");
    insertFeedState(libraryB, feedUrl, "\"etag-b\"");

    assertThat(countRows("rss_feed_state")).isEqualTo(2);
  }

  @Test
  void deletingALibraryCascadesToItsOwnRssFeedStateRowOnly() throws SQLException {
    String feedUrl = "https://example.com/feed.xml";
    UUID libraryA = insertRssFeedLibrary(feedUrl);
    UUID libraryB = insertRssFeedLibrary(feedUrl);
    insertFeedState(libraryA, feedUrl, "\"etag-a\"");
    insertFeedState(libraryB, feedUrl, "\"etag-b\"");

    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM knowledge_libraries WHERE id = '" + libraryA + "'");
    }

    assertThat(feedStateExists(libraryA, feedUrl)).isFalse();
    assertThat(feedStateExists(libraryB, feedUrl)).isTrue();
  }

  @Test
  void rejectsASecondRssFeedStateRowForTheSameLibraryAndFeedUrl() throws SQLException {
    String feedUrl = "https://example.com/feed.xml";
    UUID libraryId = insertRssFeedLibrary(feedUrl);
    insertFeedState(libraryId, feedUrl, "\"etag-a\"");

    assertThatThrownBy(() -> insertFeedState(libraryId, feedUrl, "\"etag-a-again\""))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_rss_feed_state_library_feed_url");
  }

  @Test
  void deletingTheRecipientDeletesTheirNotifications() throws SQLException {
    // #862 (Epic #826, Befund B4) dropped chk_notifications_type in migration 066 - the closed
    // vocabulary is Java-enum-enforced only from there on, so an unrecognised type is deliberately
    // not asserted as rejected here anymore (it would fail against the current, correct baseline).
    UUID recipient = insertUser();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO notifications (id, organization_id, recipient_user_id, type, title,"
              + " created_at) VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', '"
              + recipient
              + "', 'LIBRARY_ASSOCIATED_TO_MIXED_SPACE', 'Titel', now())");
    }

    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM users WHERE id = '" + recipient + "'");
    }

    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT count(*) FROM notifications WHERE recipient_user_id = '"
                    + recipient
                    + "'")) {
      rs.next();
      assertThat(rs.getInt(1)).isZero();
    }
  }

  @Test
  void llmModelSeedMarkerStartsEmptyAndAcceptsExactlyOneRow() throws SQLException {
    assertThat(countRows("llm_model_seed_marker")).isZero();

    try (Statement statement = connection.createStatement()) {
      statement.execute("INSERT INTO llm_model_seed_marker (id, seeded_at) VALUES (1, now())");
    }
    assertThat(countRows("llm_model_seed_marker")).isEqualTo(1);

    assertThatThrownBy(
            () ->
                connection
                    .createStatement()
                    .execute("INSERT INTO llm_model_seed_marker (id, seeded_at) VALUES (2, now())"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_llm_model_seed_marker_singleton");
  }

  // ---------------------------------------------------------------------------------------------
  // Organization boundary rule, ported in full from the deleted OrganizationBoundarySchemaTest
  // (#390) - a structural, schema-wide proof that closes the *class* of defect #289 was one
  // instance of: a table carrying organization_id whose foreign key to another organization_id-
  // carrying table is a plain, single-column key instead of the composite (fk_column,
  // organization_id) -> (referenced_pk, organization_id) shape the rest of the schema relies on.
  // ---------------------------------------------------------------------------------------------

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
   * Sonderfall {@code users}: {@code users} itself carries {@code organization_id}, so every table
   * referencing it falls under the rule above - this asserts that remains true today, i.e. that
   * {@link #everyOrganizationScopedForeignKeyIsComposite()} is not vacuously green because nothing
   * references {@code users} at all.
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
   * {@code organizations} (the tenant root - {@code id}, {@code name}, {@code created_at}) carries
   * no {@code organization_id} column of its own, so it never enters {@link
   * #tablesWithOrganizationId(Connection)} and a plain single-column {@code fk_*_organization} onto
   * it is correctly outside the composite-key rule's scope. That followed only implicitly from the
   * column being absent; this test makes it an explicit, checked fact instead, the same way {@link
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
   * Permanent negative test: proves the check itself catches a single-column foreign key between
   * two organization-scoped tables, in a pair of tables created here for exactly this purpose - not
   * by relying on today's schema happening to contain a violation (it does not, see {@link
   * #everyOrganizationScopedForeignKeyIsComposite()}).
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
   * {@link #DOCUMENTED_EXCEPTIONS} is empty in production, so without this test neither {@link
   * BoundaryException#matches(Violation)} nor the staleness check in {@link
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
   * silently accepted - see this class's "Exception list" note on {@link #DOCUMENTED_EXCEPTIONS}.
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
   * A foreign key whose only base column is {@code organization_id} itself - {@code
   * (organization_id) -> (organization_id)} - would satisfy the naive "organization_id is present
   * at a matching index" check without actually binding any real, object-identifying column. {@link
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
   * exceptions}. A pure, static function of both lists so both {@link
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
   * further column besides {@code organization_id} - a degenerate single-column {@code
   * (organization_id) -> (organization_id)} foreign key would satisfy the index check without
   * binding any actual object, so it must not count as composite. Collects every violation instead
   * of stopping at the first, so a migration author sees the complete list in one run.
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
   * applies to, determined from the schema itself, never hand-maintained. {@code organizations}
   * itself (the tenant root) is never part of this set - see {@link
   * #organizationsIsNeverPartOfTheOrganizationScopedTargetSet()} for the explicit assertion.
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
   * parent ({@code audit_log}) - without it, one constraint on a partitioned table would appear
   * once per partition here. {@code c.oid} is carried through {@link ForeignKeyRow} and used by
   * {@link #resolvedColumns(Connection, long, String, String)} instead of the constraint name: a
   * constraint name is only unique per table in Postgres, not per schema, so looking columns up by
   * name alone could silently interleave the columns of two same-named constraints on different
   * tables.
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
   * only unique per table. Note: {@code attnum} values can have gaps left by previously dropped
   * columns (invisible here, since only currently live columns are ever referenced by a live
   * constraint's {@code conkey}/{@code confkey}) - harmless for this join, which only ever resolves
   * attnums a live constraint actually references.
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
   * carve-out without a stated reason and a traceable issue is exactly the silent erosion the
   * original #390 issue body warns against.
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

  // ---------------------------------------------------------------------------------------------
  // Shared JDBC helpers
  // ---------------------------------------------------------------------------------------------

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

  private UUID insertUser() throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO users (id, subject, issuer, system_role, organization_id, created_at) "
              + "VALUES ('"
              + id
              + "', '"
              + id
              + "', 'test-issuer', 'USER', '"
              + SEEDED_ORGANIZATION_ID
              + "', now())");
    }
    return id;
  }

  /** A plain, USER-owned, UPLOAD-sourced library - {@code SYSTEM} owners no longer exist (#521). */
  private UUID insertLibrary(UUID ownerId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO knowledge_libraries "
              + "(id, organization_id, name, owner_type, owner_user_id, owner_group_id,"
              + " visibility, listed, source_type, created_at, updated_at) VALUES ('"
              + id
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', 'Bibliothek "
              + id
              + "', 'USER', '"
              + ownerId
              + "', NULL, 'PRIVATE', false, 'UPLOAD', now(), now())");
    }
    return id;
  }

  private UUID insertRssFeedLibrary(String feedUrl) throws SQLException {
    UUID ownerId = insertUser();
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO knowledge_libraries "
              + "(id, organization_id, name, owner_type, owner_user_id, owner_group_id,"
              + " visibility, listed, source_type, source_url, created_at, updated_at) VALUES ('"
              + id
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', 'Feed-Bibliothek "
              + id
              + "', 'USER', '"
              + ownerId
              + "', NULL, 'PRIVATE', false, 'RSS_FEED', '"
              + feedUrl
              + "', now(), now())");
    }
    return id;
  }

  private void insertIndexingJob(UUID libraryId, String status) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO indexing_jobs (id, status, started_at, last_progress_at, library_id,"
              + " organization_id) VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + status
              + "', now(), now(), '"
              + libraryId
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "')");
    }
  }

  private void insertDocument(UUID libraryId, String filePath) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO documents (id, file_name, file_path, status, source_type, library_id,"
              + " organization_id) VALUES ('"
              + UUID.randomUUID()
              + "', 'report.pdf', '"
              + filePath
              + "', 'INDEXED', 'HTTP_DIRECTORY', '"
              + libraryId
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "')");
    }
  }

  private void insertFeedState(UUID libraryId, String feedUrl, String etag) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO rss_feed_state (id, library_id, feed_url, etag, updated_at) VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + libraryId
              + "', '"
              + feedUrl
              + "', '"
              + etag
              + "', '"
              + Instant.now()
              + "')");
    }
  }

  private boolean feedStateExists(UUID libraryId, String feedUrl) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM rss_feed_state WHERE library_id = '"
                    + libraryId
                    + "' AND feed_url = '"
                    + feedUrl
                    + "'")) {
      result.next();
      return result.getInt(1) > 0;
    }
  }
}
