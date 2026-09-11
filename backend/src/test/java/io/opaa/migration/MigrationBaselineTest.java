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
 * Applies {@code db/changelog/changes/001-baseline.yaml} to an empty database and asserts the
 * invariants a broken baseline would violate: representative tables per schema group exist (and the
 * historical intermediate ones do not), pgvector is enabled, {@code audit_log} is partitioned and
 * owned by the restricted role, the seed rows are present, the organization-boundary
 * composite-foreign-key rule (formerly {@code OrganizationBoundarySchemaTest}, #390) still holds
 * schema-wide, and the current-state constraints the deleted per-changeset delta tests covered as a
 * side effect of testing their own transition.
 *
 * <p>Three sibling tests carry the rest: {@link AuditPrivilegeModelTest} and {@link
 * DiagnosticContextPrivilegeModelTest} for the two ADR-0015 privilege models, and {@link
 * VectorStoreExpressionIndexTest} for the two precondition-guarded {@code vector_store} changeSets.
 *
 * <p>A deleted delta test asserted a transition (schema state N-1 to N) that no longer exists after
 * the consolidation; the equivalence between the old chain and the baseline is a one-time proof
 * (see the #1492 pull request description for the pg_dump diff), not an ongoing regression guard.
 * That pull request also lists which of the deleted assertions were ported here and which were
 * deliberately dropped. Future changesets get their own delta test under this package again,
 * against a fixture chain starting from {@code db/changelog/test-master-through-baseline.yaml}.
 */
class MigrationBaselineTest extends AbstractMigrationTest {

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";

  /**
   * Every entry carries table, constraint name, a mandatory justification and the issue it was
   * created under (ported from {@code OrganizationBoundarySchemaTest}, #390 review - see {@link
   * BoundaryException}). {@link #everyOrganizationScopedForeignKeyIsComposite()} also fails if a
   * listed exception no longer describes an actual violation, so stale entries cannot linger
   * unnoticed.
   */
  private static final List<BoundaryException> DOCUMENTED_EXCEPTIONS =
      List.of(
          new BoundaryException(
              "documents",
              "fk_documents_parent",
              "Single-column self-reference added before this rule covered it again; making it"
                  + " composite is a schema change and therefore out of scope for the baseline"
                  + " consolidation that surfaced it. Tracked and to be removed with #1500.",
              "#1500"));

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
    // One representative table per baseline group (b-m) - not an exhaustive list, just enough to
    // catch a whole group silently missing from the baseline.
    List<String> representativeTables =
        List.of(
            "organizations",
            "oidc_providers",
            "spaces",
            "groups",
            "knowledge_libraries",
            "documents",
            "indexing_jobs",
            "source_sync_state",
            "chunk_full_text",
            "document_type_vocabulary",
            "library_metadata_fields",
            "chats",
            "llm_models",
            "audit_log",
            "asset_grant_history",
            "diagnostic_context_log",
            "diagnostic_impersonation_grants",
            "notifications",
            "branding_settings");
    for (String table : representativeTables) {
      assertThat(tableExists(table)).as("table %s must exist", table).isTrue();
    }
  }

  /**
   * The counterpart of the list above: tables that existed only as a step on the way to today's
   * schema must not be re-created by the consolidation (#1492) - the two per-connector sync-state
   * tables {@code source_sync_state} replaced, and the full-text backfill's poison-chunk
   * bookkeeping, which was created and dropped again while the backfill existed.
   */
  @Test
  void createsNoTableThatOnlyEverExistedBetweenTwoHistoricalChangesets() throws SQLException {
    for (String table : List.of("confluence_sync_state", "s3_sync_state", "chunk_full_text_skip")) {
      assertThat(tableExists(table)).as("table %s must not exist", table).isFalse();
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

    // The third seed row (baseline group (j), not (m) - see that group's own comment in
    // 001-baseline.yaml for why it cannot be deferred to group (m) with the other two).
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
  // Current-state invariants ported from the per-changeset tests #904 deleted (that pull request
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
  // Current-state invariants ported from the 31 delta tests the second consolidation (#1492)
  // deleted. Each of these asserts something about the schema the baseline builds today, not about
  // a transition; the #1492 pull request description lists what those classes additionally covered
  // and why it was not ported.
  // ---------------------------------------------------------------------------------------------

  @Test
  void aDiagnosticImpersonationGrantNeedsBothAScopeAndAnEnd() throws SQLException {
    UUID holder = insertUser();
    UUID scope = insertGroup("ORG_UNIT", "ou-1");

    assertThatThrownBy(() -> insertImpersonationGrant(holder, null, "12 months"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("scope_group_id");
    assertThatThrownBy(() -> insertImpersonationGrant(holder, scope, null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("valid_until");
  }

  @Test
  void aDiagnosticImpersonationGrantIsBoundedToTwelveMonthsAndANonEmptyWindow()
      throws SQLException {
    UUID holder = insertUser();
    UUID scope = insertGroup("ORG_UNIT", "ou-1");

    insertImpersonationGrant(holder, scope, "12 months");
    assertThatThrownBy(() -> insertImpersonationGrant(holder, scope, "13 months"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_diagnostic_impersonation_grants_validity");
    assertThatThrownBy(() -> insertImpersonationGrant(holder, scope, "0 months"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_diagnostic_impersonation_grants_validity");
  }

  @Test
  void aHalfRecordedRevocationOfADiagnosticImpersonationGrantIsRejected() throws SQLException {
    UUID holder = insertUser();
    UUID scope = insertGroup("ORG_UNIT", "ou-1");
    insertImpersonationGrant(holder, scope, "6 months");

    assertThatThrownBy(
            () ->
                execute(
                    "UPDATE diagnostic_impersonation_grants SET revoked_at = now()"
                        + " WHERE holder_user_id = '"
                        + holder
                        + "'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_diagnostic_impersonation_grants_revocation");
  }

  /**
   * The Diagnosesperre falls in favour of protection: a library is locked for "Sicht als" the
   * moment it is created, and the column cannot be emptied to sidestep that.
   */
  @Test
  void aNewLibraryIsLockedForDiagnosticsAndTheLockCannotBeEmptied() throws SQLException {
    UUID libraryId = insertLibrary(insertUser());

    assertThat(booleanOf("knowledge_libraries", "diagnostics_locked", libraryId)).isTrue();
    assertThatThrownBy(
            () ->
                execute(
                    "UPDATE knowledge_libraries SET diagnostics_locked = NULL WHERE id = '"
                        + libraryId
                        + "'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("diagnostics_locked");
  }

  @Test
  void libraryAndDocumentSourceTypeAcceptExactlyTheSixDeliveredConnectors() throws SQLException {
    UUID library = insertLibrary(insertUser());
    for (String sourceType :
        List.of("FILESYSTEM", "HTTP_DIRECTORY", "UPLOAD", "RSS_FEED", "CONFLUENCE", "S3")) {
      insertDocumentWithSourceType(library, "/corpus/" + sourceType, sourceType);
    }

    assertThatThrownBy(() -> insertDocumentWithSourceType(library, "/corpus/gcs", "GCS"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_documents_source_type");
    // Either source CHECK may fire first: an unknown type matches no arm of the configuration
    // constraint either, and PostgreSQL reports whichever it evaluates first.
    assertThatThrownBy(
            () -> insertSourceLibrary("GCS", null, "gs://bucket", "enc:v1:a", null, null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_source_");
  }

  @Test
  void theConfluenceArmNeedsAddressCredentialsAndEditionAndForbidsAPath() throws SQLException {
    insertSourceLibrary("CONFLUENCE", null, "https://wiki.example.org", "enc:v1:a", "CLOUD", null);

    assertThatThrownBy(
            () -> insertSourceLibrary("CONFLUENCE", null, null, "enc:v1:a", "DATA_CENTER", null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
    assertThatThrownBy(
            () ->
                insertSourceLibrary(
                    "CONFLUENCE", null, "https://wiki.example.org", null, "CLOUD", null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
    assertThatThrownBy(
            () ->
                insertSourceLibrary(
                    "CONFLUENCE", null, "https://wiki.example.org", "enc:v1:a", null, null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
    assertThatThrownBy(
            () ->
                insertSourceLibrary(
                    "CONFLUENCE",
                    "/srv/docs",
                    "https://wiki.example.org",
                    "enc:v1:a",
                    "CLOUD",
                    null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
  }

  @Test
  void theS3ArmNeedsANonEmptyScopeListAndCarriesNoConfluenceEdition() throws SQLException {
    String settings = "{\"region\": \"eu-central-1\", \"scopes\": [\"bucket/prefix\"]}";
    insertSourceLibrary("S3", null, "https://s3.example.org", "enc:v1:a", null, settings);

    assertThatThrownBy(
            () -> insertSourceLibrary("S3", null, "https://s3.example.org", "enc:v1:a", null, null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
    assertThatThrownBy(
            () ->
                insertSourceLibrary(
                    "S3", null, "https://s3.example.org", "enc:v1:a", null, "{\"scopes\": []}"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
    // jsonb_typeof of a non-array is not 'array', and jsonb_array_length would raise instead of
    // returning false - the CHECK's CASE is what turns that into a plain rejection.
    assertThatThrownBy(
            () ->
                insertSourceLibrary(
                    "S3",
                    null,
                    "https://s3.example.org",
                    "enc:v1:a",
                    null,
                    "{\"scopes\": \"bucket\"}"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
    assertThatThrownBy(
            () ->
                insertSourceLibrary(
                    "S3", null, "https://s3.example.org", "enc:v1:a", "CLOUD", settings))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
  }

  @Test
  void aSourceTypeWithoutAConnectorConfigurationCarriesNeitherEditionNorSettings()
      throws SQLException {
    String settings = "{\"scopes\": [\"bucket/prefix\"]}";

    assertThatThrownBy(() -> insertSourceLibrary("UPLOAD", null, null, null, "CLOUD", null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
    assertThatThrownBy(() -> insertSourceLibrary("UPLOAD", null, null, null, null, settings))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
    assertThatThrownBy(
            () ->
                insertSourceLibrary(
                    "HTTP_DIRECTORY", null, "https://files.example.org/", null, null, settings))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_source_configuration");
    insertSourceLibrary("FILESYSTEM", "/srv/docs", null, null, null, null);
    insertSourceLibrary("RSS_FEED", null, "https://example.org/feed.xml", null, null, null);
  }

  @Test
  void anIndexingRunDeclaresOneOfThreeRunModesAndOneOfThreeTriggerSources() throws SQLException {
    UUID library = insertLibrary(insertUser());

    for (String runMode : List.of("FULL", "INCREMENTAL", "EVENT")) {
      insertIndexingJob(library, "COMPLETED", runMode, "MANUAL");
    }
    for (String trigger : List.of("MANUAL", "SCHEDULED", "WEBHOOK")) {
      insertIndexingJob(library, "COMPLETED", "FULL", trigger);
    }

    assertThatThrownBy(() -> insertIndexingJob(library, "COMPLETED", "DELTA", "MANUAL"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_indexing_jobs_run_mode");
    assertThatThrownBy(() -> insertIndexingJob(library, "COMPLETED", "FULL", "API"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_indexing_jobs_triggered_by");
  }

  /**
   * Deleting a parent document is application code, never a database cascade (ADR-0022): a cascade
   * would drop the attachment row and leave its pgvector chunks orphaned.
   */
  @Test
  void deletingAParentDocumentThatStillHasAChildFailsInsteadOfCascading() throws SQLException {
    UUID library = insertLibrary(insertUser());
    UUID parent = insertDocument(library, "/corpus/mail.eml");
    UUID child = insertAttachment(library, "/corpus/mail.eml/0/anlage.pdf", parent, null);

    assertThatThrownBy(() -> execute("DELETE FROM documents WHERE id = '" + parent + "'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_documents_parent");

    execute("DELETE FROM documents WHERE id = '" + child + "'");
    execute("DELETE FROM documents WHERE id = '" + parent + "'");
    assertThat(countRows("documents")).isZero();
  }

  /**
   * Upload dedup is a promise about what users upload, not about content derived from it: the
   * unique index is partial on parentless rows, so two mails carrying an identical attachment do
   * not collide (#1218).
   */
  @Test
  void uploadChecksumDedupAppliesToParentlessRowsOnly() throws SQLException {
    UUID library = insertLibrary(insertUser());
    UUID firstMail = insertDocument(library, "/uploads/a.eml");
    UUID secondMail = insertDocument(library, "/uploads/b.eml");

    insertAttachment(library, "/uploads/a.eml/0/anlage.pdf", firstMail, "same-bytes");
    insertAttachment(library, "/uploads/b.eml/0/anlage.pdf", secondMail, "same-bytes");
    insertAttachment(library, "/uploads/plain.pdf", null, "duplicate");

    assertThatThrownBy(() -> insertAttachment(library, "/uploads/other.pdf", null, "duplicate"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_documents_library_checksum");
    insertAttachment(insertLibrary(insertUser()), "/uploads/plain.pdf", null, "duplicate");
  }

  @Test
  void sourceSyncStateHoldsExactlyOneRowPerLibraryAndDisappearsWithIt() throws SQLException {
    UUID library = insertLibrary(insertUser());
    insertSourceSyncState(library);

    assertThatThrownBy(() -> insertSourceSyncState(library))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_source_sync_state_library");
    assertThatThrownBy(() -> insertSourceSyncState(UUID.randomUUID()))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_source_sync_state_library");

    execute("DELETE FROM knowledge_libraries WHERE id = '" + library + "'");
    assertThat(countRows("source_sync_state")).isZero();
  }

  @Test
  void aDocumentTypeOutsideTheVocabularyIsNotStorable() throws SQLException {
    UUID document = insertDocument(insertLibrary(insertUser()), "/uploads/a.pdf");

    insertVocabularyValue(document, "DIENSTANWEISUNG");
    assertThatThrownBy(
            () ->
                insertVocabularyValue(
                    insertDocument(insertLibrary(insertUser()), "/uploads/b.pdf"), "DA"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_document_metadata_values_vocabulary");
  }

  @Test
  void aCoreFieldValueIsPinnedToExactlyOneValueColumn() throws SQLException {
    UUID document = insertDocument(insertLibrary(insertUser()), "/uploads/a.pdf");

    assertThatThrownBy(
            () -> insertMetadataValue(document, "document_type", "SET", "Vermerk", null, "MANUAL"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_document_metadata_values_core_field_type");
    assertThatThrownBy(() -> insertMetadataValue(document, "title", "SET", null, null, "MANUAL"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_document_metadata_values_one_value");
    assertThatThrownBy(
            () ->
                insertMetadataValue(document, "document_date", "SET", null, "2026-03-12", "MANUAL"))
        .as("a date without its precision loses the only thing that makes it readable")
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_document_metadata_values_date_has_precision");
  }

  @Test
  void confidenceIsOnlyStorableWithADerivedOriginAndAnAutomaticValueCarriesItsVersion()
      throws SQLException {
    UUID document = insertDocument(insertLibrary(insertUser()), "/uploads/a.pdf");

    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO document_metadata_values (id, document_id, field_key, text_value,"
                        + " origin, extraction_version, confidence, created_at, updated_at) VALUES"
                        + " ('"
                        + UUID.randomUUID()
                        + "', '"
                        + document
                        + "', 'title', 'Titel', 'DETERMINISTIC', 1, 0.9, now(), now())"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_document_metadata_values_confidence_only_derived");
    assertThatThrownBy(
            () -> insertMetadataValue(document, "title", "SET", "Titel", null, "DETERMINISTIC"))
        .as("only a manual value may omit the extraction version")
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_document_metadata_values_extraction_version");
  }

  @Test
  void notDeterminableIsOnlyStorableManuallyAndWithoutAValue() throws SQLException {
    UUID document = insertDocument(insertLibrary(insertUser()), "/uploads/a.pdf");

    assertThatThrownBy(
            () ->
                insertMetadataValue(document, "title", "NOT_DETERMINABLE", "Titel", null, "MANUAL"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_document_metadata_values_one_value");
    insertMetadataValue(document, "title", "NOT_DETERMINABLE", null, null, "MANUAL");
  }

  @Test
  void oneMetadataValueRowPerDocumentAndFieldAndTheyDieWithTheDocument() throws SQLException {
    UUID document = insertDocument(insertLibrary(insertUser()), "/uploads/a.pdf");
    insertMetadataValue(document, "title", "SET", "Erster Titel", null, "MANUAL");

    assertThatThrownBy(
            () -> insertMetadataValue(document, "title", "SET", "Zweiter Titel", null, "MANUAL"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_document_metadata_values_document_field");

    execute("DELETE FROM documents WHERE id = '" + document + "'");
    assertThat(countRows("document_metadata_values")).isZero();
  }

  /** The Aufnahmeregel of the specification, written into the database (#1071). */
  @Test
  void aLibraryMetadataFieldMustServeTheFilterOrTheContextPrefix() throws SQLException {
    UUID library = insertLibrary(insertUser());

    assertThatThrownBy(() -> insertLibraryField(library, "ohne_wirkung", false, false, null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_library_metadata_fields_retrieval_effect");
    insertLibraryField(library, "fassung", true, false, null);
    insertLibraryField(library, "gremium", false, true, null);
  }

  @Test
  void atMostTwoLibraryFieldsCarryACitationPositionPerLibrary() throws SQLException {
    UUID library = insertLibrary(insertUser());
    insertLibraryField(library, "fassung", true, false, 1);
    insertLibraryField(library, "gremium", true, false, 2);

    assertThatThrownBy(() -> insertLibraryField(library, "projekt", true, false, 1))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_library_metadata_fields_citation_position");
    assertThatThrownBy(() -> insertLibraryField(library, "phase", true, false, 3))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_library_metadata_fields_citation_position");
    insertLibraryField(insertLibrary(insertUser()), "fassung", true, false, 1);
  }

  /**
   * "A document carrying a value the schema no longer has" is not a reachable state: the value is a
   * real foreign key and removing a list entry a document still carries is blocked.
   */
  @Test
  void aLibraryValueStillCarriedByADocumentIsNotRemovable() throws SQLException {
    UUID library = insertLibrary(insertUser());
    UUID field = insertLibraryField(library, "fassung", true, false, null);
    UUID value = insertLibraryFieldValue(field, "FASSUNG_2026");
    UUID document = insertDocument(library, "/uploads/a.pdf");

    assertThatThrownBy(() -> insertLibraryValue(document, "fassung", field, value))
        .as("the lib: namespace and the field reference are pinned to each other")
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_document_metadata_values_library_field");
    insertLibraryValue(document, "lib:fassung", field, value);

    assertThatThrownBy(
            () -> execute("DELETE FROM library_metadata_field_values WHERE id = '" + value + "'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_document_metadata_values_library_value");
  }

  @Test
  void aDocumentTypeEndingNeedsAKnownDokumentartAndAPrefixOfAtLeastOneCharacter()
      throws SQLException {
    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO document_type_suffixes (code, suffix) VALUES"
                        + " ('RUNDSCHREIBEN', 'rundschreiben')"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_document_type_suffixes_code");
    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO document_type_suffixes (code, suffix, min_prefix_length) VALUES"
                        + " ('VERMERK', 'merk', 0)"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_document_type_suffixes_min_prefix_length");

    execute("DELETE FROM document_type_vocabulary WHERE code = 'VERMERK'");
    assertThat(countWhere("document_type_suffixes", "code = 'VERMERK'")).isZero();
    assertThat(countWhere("document_type_suffix_exclusions", "code = 'VERMERK'")).isZero();
    assertThat(countWhere("document_type_synonyms", "code = 'VERMERK'")).isZero();
  }

  /**
   * A lower-cased abbreviation collides with everyday German words ("da"), which would empty an
   * otherwise unambiguous Dokumentart - so the delivered synonym list carries no short token. The
   * seed's actual content is reconciled elsewhere ({@link
   * DocumentTypeVocabularySeedReconciliationTest}); this is the rule that content must obey.
   */
  @Test
  void noDeliveredSynonymIsShorterThanFourCharacters() throws SQLException {
    assertThat(countWhere("document_type_synonyms", "length(synonym) < 4")).isZero();
  }

  @Test
  void rejectsASecondOidcProviderWhoseIssuerDiffersOnlyInTrailingSlashes() throws SQLException {
    insertOidcProvider("Beschäftigte", "https://idp.example/realms/a/", true);

    assertThatThrownBy(() -> insertOidcProvider("Partner", "https://idp.example/realms/a", false))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ux_oidc_providers_issuer_uri_normalized");
    assertThatThrownBy(() -> insertOidcProvider("Partner", "https://idp.example/realms/a//", false))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ux_oidc_providers_issuer_uri_normalized");

    // Stored byte for byte: a token's "iss" claim is compared against it unchanged.
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT issuer_uri FROM oidc_providers")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString("issuer_uri")).isEqualTo("https://idp.example/realms/a/");
    }
  }

  @Test
  void allowsAtMostOneDefaultOidcProviderAndSeedsItsMarkerAtMostOnce() throws SQLException {
    insertOidcProvider("Beschäftigte", "https://idp.example/realms/a", true);
    insertOidcProvider("Partner", "https://idp.example/realms/b", false);
    insertOidcProvider("Land", "https://idp.example/realms/c", false);

    assertThatThrownBy(
            () -> insertOidcProvider("Zweiter Standard", "https://idp.example/realms/d", true))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ux_oidc_providers_single_default");

    assertThat(countRows("oidc_provider_seed_marker")).isZero();
    execute("INSERT INTO oidc_provider_seed_marker (id, seeded_at) VALUES (1, now())");
    assertThatThrownBy(
            () ->
                execute("INSERT INTO oidc_provider_seed_marker (id, seeded_at) VALUES (2, now())"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_oidc_provider_seed_marker_singleton");
  }

  @Test
  void onlyTheTwoKnownRejectionReasonsAreStorableAndTheCounterRowStartsAtZero()
      throws SQLException {
    UUID library = insertLibrary(insertUser());
    UUID document = insertDocument(library, "/uploads/a.pdf");

    insertRejection(library, document, "BELOW_THRESHOLD");
    insertRejection(library, document, "OUTSIDE_VOCABULARY");
    assertThatThrownBy(() -> insertRejection(library, document, "ACCEPTED"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_metadata_model_rejections_reason");

    execute("INSERT INTO metadata_model_extraction_stats (library_id) VALUES ('" + library + "')");
    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO metadata_model_extraction_stats (library_id) VALUES ('"
                        + library
                        + "')"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("metadata_model_extraction_stats_pkey");
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT calls, accepted_values, rejected_below_threshold,"
                    + " rejected_outside_vocabulary, failures, rejected_pool_full,"
                    + " keywords_assigned, last_call_at FROM metadata_model_extraction_stats")) {
      assertThat(rs.next()).isTrue();
      for (String column :
          List.of(
              "calls",
              "accepted_values",
              "rejected_below_threshold",
              "rejected_outside_vocabulary",
              "failures",
              "rejected_pool_full",
              "keywords_assigned")) {
        assertThat(rs.getLong(column)).as("counter %s starts at zero", column).isZero();
      }
      assertThat(rs.getTimestamp("last_call_at")).isNull();
    }
  }

  @Test
  void aKeywordIsStoredOncePerDocumentAndDiesWithIt() throws SQLException {
    UUID library = insertLibrary(insertUser());
    UUID document = insertDocument(library, "/uploads/a.pdf");
    insertKeyword(document, library, "abfallentsorgung");

    assertThatThrownBy(() -> insertKeyword(document, library, "abfallentsorgung"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uq_document_keywords_document_keyword");

    execute("DELETE FROM documents WHERE id = '" + document + "'");
    assertThat(countRows("document_keywords")).isZero();
  }

  /**
   * A provider group's external id is namespaced per provider, so two providers delivering a group
   * of the same name yield two groups rather than colliding on {@code
   * uk_groups_organization_external_id}.
   */
  @Test
  void sameNamedGroupsOfTwoIdentityProvidersStaySeparate() throws SQLException {
    UUID first = insertGroupNamed("IDENTITY_PROVIDER", "oidc:provider-a:Sachbearbeitung", "Sach");
    UUID second = insertGroupNamed("IDENTITY_PROVIDER", "oidc:provider-b:Sachbearbeitung", "Sach");

    assertThat(first).isNotEqualTo(second);
    assertThat(countWhere("groups", "kind = 'IDENTITY_PROVIDER'")).isEqualTo(2);
    assertThatThrownBy(
            () ->
                insertGroupNamed("IDENTITY_PROVIDER", "oidc:provider-a:Sachbearbeitung", "Nochmal"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_groups_organization_external_id");
    assertThatThrownBy(() -> insertGroupNamed("EVERYONE", "everyone", "Alle"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_groups_kind");
  }

  @Test
  void chunkFullTextIsKeyedByChunkIdAndDefaultsItsAnalysisVersionToOne() throws SQLException {
    UUID chunkId = UUID.randomUUID();
    insertChunkFullText(chunkId, "Die Gebührensatzung der Stadt");

    assertThatThrownBy(() -> insertChunkFullText(chunkId, "Ein anderer Text"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chunk_full_text_pkey");
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT content_tsv_version FROM chunk_full_text")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).isEqualTo(1);
    }
  }

  @Test
  void theGinIndexOnChunkFullTextIsValidAndAnswersAFullTextQuery() throws SQLException {
    insertChunkFullText(UUID.randomUUID(), "Die Gebührensatzung der Stadt");

    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT indisvalid FROM pg_index WHERE indexrelid ="
                    + " 'idx_chunk_full_text_content_tsv'::regclass")) {
      assertThat(rs.next()).as("the GIN index must exist").isTrue();
      assertThat(rs.getBoolean(1)).as("and must have finished building").isTrue();
    }
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT count(*) FROM chunk_full_text WHERE content_tsv @@"
                    + " to_tsquery('german', 'gebührensatzung')")) {
      rs.next();
      assertThat(rs.getInt(1)).isEqualTo(1);
    }
  }

  /**
   * Not just "the index exists": the predicate is what makes it a cheap scan for {@code
   * LowChunkDocumentAuditService}, which only ever asks about indexed documents.
   */
  @Test
  void theLowChunkAuditIndexIsPartialOnIndexedDocuments() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT indexdef FROM pg_indexes WHERE indexname ="
                    + " 'idx_documents_indexed_chunk_count'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString("indexdef"))
          .contains("(organization_id, chunk_count)")
          .contains("WHERE ((status)::text = 'INDEXED'::text)");
    }
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
   * by relying on today's schema happening to contain a violation (its only one is documented, see
   * {@link #DOCUMENTED_EXCEPTIONS}).
   */
  @Test
  void aSingleColumnForeignKeyBetweenTwoOrganizationScopedTablesIsDetectedAsAViolation()
      throws SQLException {
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
  void aDocumentedExceptionCoversTheMatchingViolationAndIsNotStale() throws SQLException {
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
    return countWhere(table, "true");
  }

  private long countWhere(String table, String whereClause) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery("SELECT count(*) FROM " + table + " WHERE " + whereClause)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private void execute(String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private boolean booleanOf(String table, String column, UUID id) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT " + column + " FROM " + table + " WHERE id = '" + id + "'")) {
      rs.next();
      return rs.getBoolean(1);
    }
  }

  private String quoted(String value) {
    return value == null ? "NULL" : "'" + value + "'";
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

  private UUID insertDocument(UUID libraryId, String filePath) throws SQLException {
    return insertDocumentWithSourceType(libraryId, filePath, "HTTP_DIRECTORY");
  }

  private UUID insertDocumentWithSourceType(UUID libraryId, String filePath, String sourceType)
      throws SQLException {
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO documents (id, file_name, file_path, status, source_type, library_id,"
            + " organization_id) VALUES ('"
            + id
            + "', 'report.pdf', '"
            + filePath
            + "', 'INDEXED', '"
            + sourceType
            + "', '"
            + libraryId
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "')");
    return id;
  }

  /** An UPLOAD-sourced row that may hang off a parent document and carry a dedup checksum. */
  private UUID insertAttachment(UUID libraryId, String filePath, UUID parent, String checksum)
      throws SQLException {
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO documents (id, file_name, file_path, status, source_type, library_id,"
            + " organization_id, parent_document_id, checksum) VALUES ('"
            + id
            + "', 'anlage.pdf', '"
            + filePath
            + "', 'INDEXED', 'UPLOAD', '"
            + libraryId
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', "
            + (parent == null ? "NULL" : "'" + parent + "'")
            + ", "
            + quoted(checksum)
            + ")");
    return id;
  }

  private UUID insertGroup(String kind, String externalId) throws SQLException {
    return insertGroupNamed(kind, externalId, "Gruppe " + externalId);
  }

  private UUID insertGroupNamed(String kind, String externalId, String name) throws SQLException {
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO groups (id, organization_id, kind, name, external_id) VALUES ('"
            + id
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', '"
            + kind
            + "', '"
            + name
            + "', "
            + quoted(externalId)
            + ")");
    return id;
  }

  /** {@code duration} is a PostgreSQL interval literal; {@code null} leaves the end open. */
  private void insertImpersonationGrant(UUID holder, UUID scopeGroup, String duration)
      throws SQLException {
    execute(
        "INSERT INTO diagnostic_impersonation_grants (id, organization_id, holder_user_id,"
            + " scope_group_id, valid_from, valid_until, granted_by_user_id, granted_at) VALUES ('"
            + UUID.randomUUID()
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', '"
            + holder
            + "', "
            + (scopeGroup == null ? "NULL" : "'" + scopeGroup + "'")
            + ", now(), "
            + (duration == null ? "NULL" : "now() + interval '" + duration + "'")
            + ", '"
            + holder
            + "', now())");
  }

  private UUID insertSourceLibrary(
      String sourceType,
      String sourcePath,
      String sourceUrl,
      String credentials,
      String confluenceEdition,
      String sourceSettings)
      throws SQLException {
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type, owner_user_id,"
            + " visibility, listed, source_type, source_path, source_url, source_credentials,"
            + " source_confluence_edition, source_settings) VALUES ('"
            + id
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', 'Bibliothek "
            + id
            + "', 'USER', '"
            + insertUser()
            + "', 'PRIVATE', false, '"
            + sourceType
            + "', "
            + quoted(sourcePath)
            + ", "
            + quoted(sourceUrl)
            + ", "
            + quoted(credentials)
            + ", "
            + quoted(confluenceEdition)
            + ", "
            + (sourceSettings == null ? "NULL" : "'" + sourceSettings + "'::jsonb")
            + ")");
    return id;
  }

  private void insertIndexingJob(UUID libraryId, String status, String runMode, String triggeredBy)
      throws SQLException {
    execute(
        "INSERT INTO indexing_jobs (id, status, run_mode, triggered_by, started_at,"
            + " last_progress_at, library_id, organization_id) VALUES ('"
            + UUID.randomUUID()
            + "', '"
            + status
            + "', '"
            + runMode
            + "', '"
            + triggeredBy
            + "', now(), now(), '"
            + libraryId
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "')");
  }

  private void insertSourceSyncState(UUID libraryId) throws SQLException {
    execute(
        "INSERT INTO source_sync_state (id, library_id, updated_at) VALUES ('"
            + UUID.randomUUID()
            + "', '"
            + libraryId
            + "', now())");
  }

  private void insertVocabularyValue(UUID documentId, String code) throws SQLException {
    execute(
        "INSERT INTO document_metadata_values (id, document_id, field_key, vocabulary_code, origin,"
            + " extraction_version, created_at, updated_at) VALUES ('"
            + UUID.randomUUID()
            + "', '"
            + documentId
            + "', 'document_type', '"
            + code
            + "', 'DETERMINISTIC', 1, now(), now())");
  }

  /** Never sets {@code extraction_version} - only a MANUAL value may omit it. */
  private void insertMetadataValue(
      UUID documentId,
      String fieldKey,
      String valueState,
      String textValue,
      String isoDate,
      String origin)
      throws SQLException {
    execute(
        "INSERT INTO document_metadata_values (id, document_id, field_key, value_state, text_value,"
            + " date_value, origin, created_at, updated_at) VALUES ('"
            + UUID.randomUUID()
            + "', '"
            + documentId
            + "', '"
            + fieldKey
            + "', '"
            + valueState
            + "', "
            + quoted(textValue)
            + ", "
            + (isoDate == null ? "NULL" : "'" + isoDate + "'::date")
            + ", '"
            + origin
            + "', now(), now())");
  }

  private UUID insertLibraryField(
      UUID libraryId,
      String fieldKey,
      boolean filterEnabled,
      boolean contextPrefixEnabled,
      Integer citationPosition)
      throws SQLException {
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO library_metadata_fields (id, library_id, field_key, label, field_type,"
            + " filter_enabled, context_prefix_enabled, citation_enabled, citation_position,"
            + " sort_order, created_at, updated_at) VALUES ('"
            + id
            + "', '"
            + libraryId
            + "', '"
            + fieldKey
            + "', 'Label', 'SELECT', "
            + filterEnabled
            + ", "
            + contextPrefixEnabled
            + ", "
            + (citationPosition != null)
            + ", "
            + (citationPosition == null ? "NULL" : citationPosition)
            + ", 10, now(), now())");
    return id;
  }

  private UUID insertLibraryFieldValue(UUID fieldId, String code) throws SQLException {
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO library_metadata_field_values (id, field_id, code, label, sort_order) VALUES"
            + " ('"
            + id
            + "', '"
            + fieldId
            + "', '"
            + code
            + "', 'Label', 10)");
    return id;
  }

  private void insertLibraryValue(UUID documentId, String fieldKey, UUID fieldId, UUID valueId)
      throws SQLException {
    execute(
        "INSERT INTO document_metadata_values (id, document_id, field_key, text_value,"
            + " library_field_id, library_value_id, origin, created_at, updated_at) VALUES ('"
            + UUID.randomUUID()
            + "', '"
            + documentId
            + "', '"
            + fieldKey
            + "', 'Fassung 2026', '"
            + fieldId
            + "', '"
            + valueId
            + "', 'MANUAL', now(), now())");
  }

  private void insertOidcProvider(String displayName, String issuerUri, boolean isDefault)
      throws SQLException {
    execute(
        "INSERT INTO oidc_providers (id, display_name, enabled, is_default, issuer_uri, client_id)"
            + " VALUES (gen_random_uuid(), '"
            + displayName
            + "', true, "
            + isDefault
            + ", '"
            + issuerUri
            + "', 'opaa-frontend')");
  }

  private void insertRejection(UUID libraryId, UUID documentId, String reason) throws SQLException {
    execute(
        "INSERT INTO metadata_model_rejections (id, library_id, document_id, field_key,"
            + " proposed_value, confidence, reason) VALUES ('"
            + UUID.randomUUID()
            + "', '"
            + libraryId
            + "', '"
            + documentId
            + "', 'document_type', 'Rundschreiben', 0.4, '"
            + reason
            + "')");
  }

  private void insertKeyword(UUID documentId, UUID libraryId, String keyword) throws SQLException {
    execute(
        "INSERT INTO document_keywords (id, document_id, library_id, keyword) VALUES ('"
            + UUID.randomUUID()
            + "', '"
            + documentId
            + "', '"
            + libraryId
            + "', '"
            + keyword
            + "')");
  }

  private void insertChunkFullText(UUID chunkId, String content) throws SQLException {
    execute(
        "INSERT INTO chunk_full_text (chunk_id, document_id, library_id, content_tsv) VALUES ('"
            + chunkId
            + "', '"
            + UUID.randomUUID()
            + "', '"
            + UUID.randomUUID()
            + "', to_tsvector('german', '"
            + content
            + "'))");
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
