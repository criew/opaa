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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Applies every migration to an empty database and asserts what spans all modules: one baseline per
 * logical module in dependency order, the complete table inventory, pgvector, the delivered seed
 * rows, the partitioned {@code audit_log} owned by the restricted role, and the
 * organization-boundary composite-foreign-key rule on the delivered schema.
 *
 * <p>The invariants of the individual modules live in one class per module ({@link
 * IdentityBaselineTest}, {@link RightsBaselineTest}, {@link KnowledgeBaselineTest}, {@link
 * ConnectorsBaselineTest}, {@link WorkspaceBaselineTest}, {@link AssistantBaselineTest}, {@link
 * ExternalBaselineTest}); the privilege models and the retention deletion have classes of their
 * own.
 */
class MigrationBaselineTest extends AbstractBaselineTest {

  /**
   * Every entry carries table, constraint name, a mandatory justification and the issue it was
   * created under (see {@link BoundaryException}). {@link
   * #everyOrganizationScopedForeignKeyIsComposite()} also fails if a listed exception no longer
   * describes an actual violation, so stale entries cannot linger unnoticed. Empty today: the
   * schema holds the rule without exception.
   */
  private static final List<BoundaryException> DOCUMENTED_EXCEPTIONS = List.of();

  /** The rows the migrations deliver; every other table starts empty. */
  private static final Map<String, Long> SEEDED_ROW_COUNTS =
      Map.ofEntries(
          Map.entry("audit_retention_settings", 1L),
          Map.entry("branding_settings", 1L),
          Map.entry("capability_grant_history", 8L),
          Map.entry("capability_grants", 8L),
          Map.entry("chat_locked_account_cleanup_state", 1L),
          Map.entry("connection_log_retention_settings", 1L),
          Map.entry("diagnostic_context_retention_settings", 1L),
          Map.entry("document_type_suffix_exclusions", 14L),
          Map.entry("document_type_suffixes", 7L),
          Map.entry("document_type_synonyms", 34L),
          Map.entry("document_type_vocabulary", 9L),
          Map.entry("external_access_settings", 1L),
          Map.entry("link_target_settings", 1L),
          Map.entry("local_auth_settings", 1L),
          Map.entry("mail_settings", 1L),
          Map.entry("organizations", 1L),
          Map.entry("permission_history_retention_settings", 1L),
          Map.entry("self_disclosure_settings", 1L));

  // ---------------------------------------------------------------------------------------------
  // Baseline smoke tests
  // ---------------------------------------------------------------------------------------------

  /** One baseline per logical module, in the order modules may depend on each other. */
  @Test
  void startsWithOneBaselinePerModuleInDependencyOrder() throws SQLException {
    List<String> executed = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT script FROM flyway_schema_history WHERE version LIKE '1.%'"
                    + " ORDER BY installed_rank")) {
      while (rs.next()) {
        executed.add(rs.getString(1));
      }
    }

    assertThat(executed)
        .containsExactly(
            "foundation/V1.1__foundation_baseline.sql",
            "identity/V1.2__identity_baseline.sql",
            "rights/V1.3__rights_baseline.sql",
            "knowledge/V1.4__knowledge_baseline.sql",
            "connectors/V1.5__connectors_baseline.sql",
            "connections/V1.6__connections_baseline.sql",
            "workspace/V1.7__workspace_baseline.sql",
            "assistant/V1.8__assistant_baseline.sql",
            "external/V1.9__external_baseline.sql");
  }

  /**
   * The complete table inventory, compared in both directions: a table missing from the migrations
   * fails here, and so does one the migrations create without this list naming it.
   */
  @Test
  void createsEveryTableOfEveryModule() throws SQLException {
    assertThat(baseTableNames())
        .containsExactlyInAnyOrder(
            // foundation
            "backend_instance_fingerprint_key",
            "backend_instances",
            "installation_counters",
            "organizations",
            "outbound_proxy_settings",
            "shedlock",
            // identity
            "admin_review_reminders",
            "audit_actor_pseudonyms",
            "audit_chain_checkpoints",
            "audit_chat_cleanup_hold_monthly_counts",
            "audit_chain_seals",
            "audit_incident_scope_case_counts",
            "audit_incident_scope_grants",
            "audit_incident_scope_monthly_counts",
            "audit_incident_scope_retention_settings",
            "audit_log",
            "audit_retention_settings",
            "branding_settings",
            "link_target_settings",
            "local_action_tokens",
            "local_admin_seed_marker",
            "local_auth_settings",
            "local_credentials",
            "local_refresh_tokens",
            "local_revoked_tokens",
            "mail_outbox",
            "mail_settings",
            "mail_templates",
            "notifications",
            "oidc_provider_removals",
            "oidc_provider_seed_marker",
            "oidc_providers",
            "self_disclosure_settings",
            "self_disclosure_usage",
            "siem_export_cursor",
            "users",
            // rights
            "account_state_history",
            "asset_favorites",
            "asset_grant_history",
            "asset_grants",
            "asset_ownership_history",
            "asset_visibility_history",
            "assets",
            "capability_grant_history",
            "capability_grants",
            "directory_connectors",
            "directory_sync_pending_plans",
            "directory_sync_settings",
            "directory_sync_status",
            "group_membership_history",
            "group_memberships",
            "group_stewards",
            "groups",
            "permission_history_retention_settings",
            "permission_transfer_objects",
            "permission_transfers",
            "succession_cases",
            "succession_settings",
            // knowledge
            "chunk_full_text",
            "document_keywords",
            "document_metadata_values",
            "document_type_suffix_exclusions",
            "document_type_suffixes",
            "document_type_synonyms",
            "document_type_vocabulary",
            "documents",
            "embedding_index",
            "embedding_rate_measurements",
            "full_sync_rhythm_settings",
            "indexing_jobs",
            "indexing_run_events",
            "installation_state",
            "installation_steps",
            "knowledge_libraries",
            "library_folders",
            "library_maintenance_skips",
            "library_metadata_field_values",
            "library_metadata_fields",
            "library_metadata_schema_changes",
            "llm_model_seed_marker",
            "llm_models",
            "metadata_model_extraction_stats",
            "metadata_model_rejections",
            "model_role_observations",
            "private_storage_quota_settings",
            "source_event_batches",
            "source_sync_presence",
            "source_sync_revisits",
            "source_sync_state",
            "embedding_model",
            "embedding_model_previous",
            "rerank_model",
            "transcription_model",
            // vector_store is no migration's table: the installation creates it (ADR-0060)
            // connectors
            "rss_feed_state",
            // connections
            "connected_accounts",
            "connection_authorizations",
            "connection_grant_revocations",
            "connection_lifecycle_settings",
            "connection_log",
            "connection_log_retention_settings",
            "connection_person_states",
            "connection_profiles",
            "connection_tokens",
            "connector_type_policies",
            "library_connections",
            // workspace
            "chat_auto_cleanup_settings",
            "diagnostic_context_log",
            "diagnostic_context_retention_settings",
            "diagnostic_impersonation_grants",
            "space_asset_associations",
            "space_membership_history",
            "space_memberships",
            "spaces",
            // assistant
            "chat_cleanup_holds",
            "chat_library_references",
            "chat_locked_account_cleanup_state",
            "chat_messages",
            "chat_note_items",
            "chat_personal_marks",
            "chats",
            "locked_account_chat_cleanup_settings",
            "prompt_libraries",
            "prompts",
            "search_status_probes",
            // external
            "external_access_settings",
            "external_access_token_libraries",
            "external_access_tokens");
  }

  /**
   * Tables that existed only as a step on the way to today's schema must not be re-created by a
   * consolidation: the per-connector sync-state tables {@code source_sync_state} replaced, the
   * full-text backfill's poison-chunk bookkeeping, the Confluence space selection that became part
   * of {@code source_settings}, and the library-only visibility history the asset shell replaced.
   */
  @Test
  void createsNoTableThatOnlyEverExistedBetweenTwoHistoricalMigrations() throws SQLException {
    for (String table :
        List.of(
            "confluence_sync_state",
            "s3_sync_state",
            "chunk_full_text_skip",
            "knowledge_library_confluence_spaces",
            "library_visibility_history")) {
      assertThat(baseTableNames()).as("table %s must not exist", table).doesNotContain(table);
    }
  }

  @Test
  void enablesPgvectorExtension() throws SQLException {
    assertThat(countWhere("pg_extension", "extname = 'vector'")).isEqualTo(1);
  }

  /**
   * The seed rows, counted against every table: the singletons, the delivered capabilities with
   * their intervals and the Dokumentart vocabulary - and nothing else. Their contents are asserted
   * in the module classes and in {@link DocumentTypeVocabularySeedReconciliationTest}.
   */
  @Test
  void seedsExactlyTheDeliveredRowsAndLeavesEveryOtherTableEmpty() throws SQLException {
    for (String table : baseTableNames()) {
      assertThat(countRows(table))
          .as("rows in %s", table)
          .isEqualTo(SEEDED_ROW_COUNTS.getOrDefault(table, 0L));
    }
    assertThat(
            stringOf("SELECT name FROM organizations WHERE id = '" + SEEDED_ORGANIZATION_ID + "'"))
        .isEqualTo("Default");
    assertThat(longOf("SELECT retention_months FROM audit_retention_settings WHERE id = 1"))
        .isEqualTo(36);
  }

  @Test
  void partitionsAuditLogByMonthAndOwnsItViaTheRestrictedRole() throws SQLException {
    // Three months back through 191 months forward (195 in total), see the baseline's own comment.
    assertThat(longOf("SELECT count(*) FROM pg_inherits WHERE inhparent = 'audit_log'::regclass"))
        .isEqualTo(195);
    assertThat(stringOf("SELECT relowner::regrole::text FROM pg_class WHERE relname = 'audit_log'"))
        .isEqualTo("opaa_audit_owner");
  }

  // ---------------------------------------------------------------------------------------------
  // Organization boundary rule, ported in full from the deleted OrganizationBoundarySchemaTest
  // - a structural, schema-wide proof that closes the *class* of defect #289 was one
  // instance of: a table carrying organization_id whose foreign key to another organization_id-
  // carrying table is a plain, single-column key instead of the composite (fk_column,
  // organization_id) -> (referenced_pk, organization_id) shape the rest of the schema relies on.
  // ---------------------------------------------------------------------------------------------

  @Test
  void everyOrganizationScopedForeignKeyIsComposite() throws Exception {
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
   * by relying on today's schema happening to contain a violation (its only one is documented, see
   * {@link #DOCUMENTED_EXCEPTIONS}).
   */
  @Test
  void aSingleColumnForeignKeyBetweenTwoOrganizationScopedTablesIsDetectedAsAViolation()
      throws Exception {
    createArtificialOrganizationScopedTablesWithASingleColumnForeignKey();

    List<Violation> violations =
        undocumentedViolations(findViolations(connection), DOCUMENTED_EXCEPTIONS);

    assertThat(violations)
        .as(
            "the rest of the schema is clean apart from its documented exceptions (see"
                + " everyOrganizationScopedForeignKeyIsComposite) - the only undocumented violation"
                + " must be the artificial one this test just created")
        .hasSize(1);
    Violation violation = violations.get(0);
    assertThat(violation.table()).isEqualTo("test_boundary_child");
    assertThat(violation.constraintName()).isEqualTo("fk_test_boundary_child_parent");
    assertThat(violation.referencedTable()).isEqualTo("test_boundary_parent");
    assertThat(violation.baseColumns()).containsExactly("parent_id");
    assertThat(violation.referencedColumns()).containsExactly("id");
  }

  /**
   * Exercises {@link BoundaryException#matches(Violation)} and the staleness check against a
   * violation created for exactly that purpose, so the exception mechanism is proven by this class
   * rather than by whatever {@link #DOCUMENTED_EXCEPTIONS} happens to contain - an assertion that
   * is never exercised is exactly the kind of untested "fix" {@code AGENTS.md}'s
   * Reproduktionsnachweis section warns against.
   */
  @Test
  void aDocumentedExceptionCoversTheMatchingViolationAndIsNotStale() throws Exception {
    createArtificialOrganizationScopedTablesWithASingleColumnForeignKey();
    List<Violation> violations = findViolations(connection);
    List<BoundaryException> exceptions = new ArrayList<>(DOCUMENTED_EXCEPTIONS);
    exceptions.add(
        new BoundaryException(
            "test_boundary_child",
            "fk_test_boundary_child_parent",
            "artificial violation created by this test, not a real exception",
            "#390"));

    assertThat(undocumentedViolations(violations, exceptions))
        .as("a documented exception naming exactly this violation must suppress it")
        .isEmpty();
    assertThat(staleExceptionDescriptions(violations, exceptions))
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
                "no longer needed - the redundant constraint this exception once covered is gone",
                "#390"));

    assertThat(staleExceptionDescriptions(violations, staleException))
        .as(
            "an exception naming a constraint that is not among today's violations must be flagged"
                + " as stale, since today's schema no longer has this"
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
  void aForeignKeyThatIsOnlyOrganizationIdIsNotAcceptedAsComposite() throws Exception {
    createArtificialOrganizationScopedTablesWithADegenerateOrganizationIdOnlyForeignKey();

    List<Violation> violations =
        undocumentedViolations(findViolations(connection), DOCUMENTED_EXCEPTIONS);

    assertThat(violations)
        .as(
            "the only undocumented violation must be the artificial degenerate one this test just"
                + " created")
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
                    + " WHERE c.table_schema = current_schema() AND c.column_name = 'organization_id'"
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
                    + " WHERE c.contype = 'f' AND bn.nspname = current_schema() AND c.conparentid = 0"
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
  // Catalogue helpers
  // ---------------------------------------------------------------------------------------------

  /**
   * Every ordinary and partitioned table in the current schema, without the partitions themselves
   * ({@code relispartition}) and without Flyway's schema history.
   */
  private List<String> baseTableNames() throws SQLException {
    List<String> tables = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = current_schema() AND c.relkind IN ('r', 'p')"
                    + " AND NOT c.relispartition"
                    + " AND c.relname <> 'flyway_schema_history'"
                    + " ORDER BY c.relname")) {
      while (rs.next()) {
        tables.add(rs.getString(1));
      }
    }
    return tables;
  }
}
