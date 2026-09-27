package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Invariants of the baseline's knowledge changeSet: knowledge libraries on the asset shell, their
 * source configuration (ADR-0038), documents, indexing runs, the full-text index, the metadata
 * schema (ADR-0024) and the model catalogue. The two {@code vector_store} indexes have their own
 * class, {@link VectorStoreExpressionIndexTest}.
 */
class KnowledgeBaselineTest extends AbstractBaselineTest {

  // ---------------------------------------------------------------------------------------------
  // Libraries on the asset shell
  // ---------------------------------------------------------------------------------------------

  /** The type row stands only next to its shell and goes when the shell goes. */
  @Test
  void aLibraryRowNeedsItsShellAndGoesWithIt() throws SQLException {
    assertRejected(
        "INSERT INTO knowledge_libraries (id, organization_id, source_type) VALUES"
            + " (gen_random_uuid(), '"
            + SEEDED_ORGANIZATION_ID
            + "', 'UPLOAD')",
        "fk_knowledge_libraries_asset");

    UUID library = insertLibrary();
    execute("DELETE FROM assets WHERE id = '" + library + "'");
    assertThat(countWhere("knowledge_libraries", "id = '" + library + "'")).isZero();
  }

  /**
   * The Diagnosesperre falls in favour of protection: a library is locked for "Sicht als" the
   * moment it is created, and the column cannot be emptied to sidestep that.
   */
  @Test
  void aNewLibraryIsLockedForDiagnosticsAndTheLockCannotBeEmptied() throws SQLException {
    UUID library = insertLibrary();

    assertThat(booleanOf(libraryColumn("diagnostics_locked", library))).isTrue();
    assertRejected(
        "UPDATE knowledge_libraries SET diagnostics_locked = NULL WHERE id = '" + library + "'",
        "diagnostics_locked");
  }

  /**
   * ADR-0038: the database checks the shape of the type key only, so a new connector needs no
   * schema change - for libraries and documents alike.
   */
  @Test
  void aSourceTypeIsAnyEnumShapedKeyForLibrariesAndDocuments() throws SQLException {
    UUID library = insertLibrary("SHAREPOINT", "https://sharepoint.example");
    insertDocumentWithSourceType(library, "/a", "SHAREPOINT");

    assertRejected(librarySql("share-point", null, null), "chk_knowledge_libraries_source_type");
    assertRejected(documentSourceTypeSql(library, "/b", "Share"), "chk_documents_source_type");
  }

  /** The settings are an object; what it contains is the connector's business, not a CHECK's. */
  @Test
  void theSourceSettingsAreAJsonObjectWhoseContentOnlyTheConnectorChecks() throws SQLException {
    UUID library = insertLibrary("S3", "https://s3.example");
    execute(settings(library, "'{\"scopes\": []}'"));
    execute(settings(library, "'{}'"));

    assertRejected(settings(library, "'[\"bucket\"]'"), "chk_knowledge_libraries_source_settings");
    assertRejected(settings(library, "'\"bucket\"'"), "chk_knowledge_libraries_source_settings");
  }

  /**
   * An upload library has no source: no address, credentials, settings or schedule, and no share
   * cap - its owner chooses every single document anyway.
   */
  @Test
  void anUploadLibraryCarriesNoSourceConfigurationNoScheduleAndNoShareCap() throws SQLException {
    UUID upload = insertLibrary();
    String where = " WHERE id = '" + upload + "'";

    assertRejected(
        "UPDATE knowledge_libraries SET source_url = 'https://x.example'" + where,
        "chk_knowledge_libraries_upload_without_source");
    assertRejected(settings(upload, "'{}'"), "chk_knowledge_libraries_upload_without_source");
    assertRejected(
        "UPDATE knowledge_libraries SET schedule_enabled = true, schedule_cron = '0 0 * * * *'"
            + where,
        "chk_knowledge_libraries_schedule");
    assertRejected(
        "UPDATE knowledge_libraries SET all_accounts_grant_allowed = false" + where,
        "chk_knowledge_libraries_share_cap_upload_unrestricted");
    assertRejected(
        "UPDATE knowledge_libraries SET listed_cap = false" + where,
        "chk_knowledge_libraries_share_cap_upload_unrestricted");
  }

  /** The share cap of a connector library is unrestrictive until an administrator lowers it. */
  @Test
  void aConnectorLibraryStartsUnrestrictedAndMayBeCapped() throws SQLException {
    UUID library = insertLibrary("RSS_FEED", "https://example.org/feed.xml");

    assertThat(booleanOf(libraryColumn("all_accounts_grant_allowed AND listed_cap", library)))
        .isTrue();
    execute(
        "UPDATE knowledge_libraries SET all_accounts_grant_allowed = false, listed_cap = false"
            + " WHERE id = '"
            + library
            + "'");
  }

  /**
   * A release for external access is time-bound: ACTIVE needs an expiry, and a library that was
   * never released carries no trace of a release - which is where every library starts.
   */
  @Test
  void aReleaseForExternalAccessIsTimeBoundAndEveryLibraryStartsUnreleased() throws SQLException {
    UUID library = insertLibrary();
    String where = " WHERE id = '" + library + "'";

    assertThat(stringOf("SELECT external_access_state FROM knowledge_libraries" + where))
        .isEqualTo("NEVER_SET");
    assertRejected(
        "UPDATE knowledge_libraries SET external_access_state = 'ACTIVE',"
            + " external_access_set_at = now()"
            + where,
        "chk_knowledge_libraries_external_access");
    assertRejected(
        "UPDATE knowledge_libraries SET external_access_set_at = now()" + where,
        "chk_knowledge_libraries_external_access");
    assertRejected(
        "UPDATE knowledge_libraries SET external_access_state = 'PAUSED',"
            + " external_access_set_at = now()"
            + where,
        "chk_knowledge_libraries_external_access");
    execute(
        "UPDATE knowledge_libraries SET external_access_state = 'ACTIVE', external_access_set_at ="
            + " now(), external_access_expires_at = now() + interval '90 days'"
            + where);
    execute("UPDATE knowledge_libraries SET external_access_state = 'WITHDRAWN'" + where);
  }

  // ---------------------------------------------------------------------------------------------
  // Documents
  // ---------------------------------------------------------------------------------------------

  /** The exact case #877 fixed: two libraries indexing the same path yield two documents. */
  @Test
  void aDocumentPathIsUniquePerLibraryOnly() throws SQLException {
    UUID first = insertLibrary();
    UUID second = insertLibrary();
    insertDocument(first, "/corpus/report.pdf");
    insertDocument(second, "/corpus/report.pdf");

    assertRejected(
        documentSourceTypeSql(first, "/corpus/report.pdf", "UPLOAD"), "uk_documents_library_path");
  }

  /**
   * Deleting a parent document is application code, never a database cascade (ADR-0022): a cascade
   * would drop the attachment row and leave its pgvector chunks orphaned. Both rows in one
   * statement go, and a parent of another organization is not nameable at all.
   */
  @Test
  void aParentDocumentBelongsToTheSameOrganizationAndIsNeverDeletedUnderItsChild()
      throws SQLException {
    UUID library = insertLibrary();
    UUID parent = insertDocument(library, "/corpus/mail.eml");
    UUID child = insertAttachment(library, "/corpus/mail.eml/0/anlage.pdf", parent, null);

    assertRejected("DELETE FROM documents WHERE id = '" + parent + "'", "fk_documents_parent");
    execute("DELETE FROM documents WHERE id IN ('" + parent + "', '" + child + "')");
    assertThat(countRows("documents")).isZero();

    UUID foreignParent = insertDocument(library, "/corpus/other.eml");
    UUID otherOrganization = insertOrganization();
    UUID foreignLibrary = insertLibraryOf(otherOrganization);
    assertRejected(
        "INSERT INTO documents (id, file_name, file_path, source_type, library_id, organization_id,"
            + " parent_document_id) VALUES (gen_random_uuid(), 'a.pdf', '/a.pdf', 'UPLOAD', '"
            + foreignLibrary
            + "', '"
            + otherOrganization
            + "', '"
            + foreignParent
            + "')",
        "fk_documents_parent");
  }

  /**
   * Upload dedup is a promise about what users upload, not about content derived from it: the
   * unique index is partial on parentless rows (#1218).
   */
  @Test
  void uploadChecksumDedupAppliesToParentlessRowsOnly() throws SQLException {
    UUID library = insertLibrary();
    UUID firstMail = insertDocument(library, "/uploads/a.eml");
    UUID secondMail = insertDocument(library, "/uploads/b.eml");

    insertAttachment(library, "/uploads/a.eml/0/anlage.pdf", firstMail, "same-bytes");
    insertAttachment(library, "/uploads/b.eml/0/anlage.pdf", secondMail, "same-bytes");
    insertAttachment(library, "/uploads/plain.pdf", null, "duplicate");

    assertRejected(
        attachmentSql(UUID.randomUUID(), library, "/uploads/other.pdf", null, "duplicate"),
        "uk_documents_library_checksum");
    insertAttachment(insertLibrary(), "/uploads/plain.pdf", null, "duplicate");
  }

  /**
   * Not just "the index exists": the predicate is what makes it a cheap scan for {@code
   * LowChunkDocumentAuditService}, which only ever asks about indexed documents.
   */
  @Test
  void theLowChunkAuditIndexIsPartialOnIndexedDocuments() throws SQLException {
    assertThat(
            stringOf(
                "SELECT indexdef FROM pg_indexes WHERE indexname ="
                    + " 'idx_documents_indexed_chunk_count'"))
        .contains("(organization_id, chunk_count)")
        .contains("WHERE ((status)::text = 'INDEXED'::text)");
  }

  // ---------------------------------------------------------------------------------------------
  // Indexing runs, source state, full-text index
  // ---------------------------------------------------------------------------------------------

  @Test
  void aLibraryRunsAtMostOneIndexingJobAtATime() throws SQLException {
    UUID first = insertLibrary();
    UUID second = insertLibrary();
    execute(indexingJobSql(first, "RUNNING", "FULL", "MANUAL"));
    execute(indexingJobSql(second, "RUNNING", "FULL", "MANUAL"));

    assertRejected(
        indexingJobSql(first, "RUNNING", "FULL", "MANUAL"), "uk_indexing_jobs_library_running");
    execute(indexingJobSql(first, "COMPLETED", "FULL", "MANUAL"));
  }

  @Test
  void anIndexingRunDeclaresOneOfThreeRunModesAndOneOfThreeTriggerSources() throws SQLException {
    UUID library = insertLibrary();
    for (String runMode : List.of("FULL", "INCREMENTAL", "EVENT")) {
      execute(indexingJobSql(library, "COMPLETED", runMode, "MANUAL"));
    }
    for (String trigger : List.of("MANUAL", "SCHEDULED", "WEBHOOK")) {
      execute(indexingJobSql(library, "COMPLETED", "FULL", trigger));
    }

    assertRejected(
        indexingJobSql(library, "COMPLETED", "DELTA", "MANUAL"), "chk_indexing_jobs_run_mode");
    assertRejected(
        indexingJobSql(library, "COMPLETED", "FULL", "API"), "chk_indexing_jobs_triggered_by");
  }

  @Test
  void sourceSyncStateHoldsExactlyOneRowPerLibraryAndDisappearsWithIt() throws SQLException {
    UUID library = insertLibrary("S3", "https://s3.example");
    execute(sourceSyncStateSql(library));

    assertRejected(sourceSyncStateSql(library), "uk_source_sync_state_library");
    assertRejected(sourceSyncStateSql(UUID.randomUUID()), "fk_source_sync_state_library");
    execute("DELETE FROM assets WHERE id = '" + library + "'");
    assertThat(countRows("source_sync_state")).isZero();
  }

  @Test
  void chunkFullTextIsKeyedByChunkIdAndDefaultsItsAnalysisVersionToOne() throws SQLException {
    UUID chunkId = UUID.randomUUID();
    execute(chunkFullTextSql(chunkId, "Die Gebührensatzung der Stadt"));

    assertRejected(chunkFullTextSql(chunkId, "Ein anderer Text"), "chunk_full_text_pkey");
    assertThat(longOf("SELECT content_tsv_version FROM chunk_full_text")).isEqualTo(1);
  }

  @Test
  void theGinIndexOnChunkFullTextIsValidAndAnswersAFullTextQuery() throws SQLException {
    execute(chunkFullTextSql(UUID.randomUUID(), "Die Gebührensatzung der Stadt"));

    assertThat(
            booleanOf(
                "SELECT indisvalid FROM pg_index WHERE indexrelid ="
                    + " 'idx_chunk_full_text_content_tsv'::regclass"))
        .isTrue();
    assertThat(
            longOf(
                "SELECT count(*) FROM chunk_full_text WHERE content_tsv @@"
                    + " to_tsquery('german', 'gebührensatzung')"))
        .isEqualTo(1);
  }

  // ---------------------------------------------------------------------------------------------
  // Metadata schema (ADR-0024)
  // ---------------------------------------------------------------------------------------------

  @Test
  void aDocumentTypeOutsideTheVocabularyIsNotStorable() throws SQLException {
    UUID document = insertDocument(insertLibrary(), "/uploads/a.pdf");
    execute(vocabularyValueSql(document, "DIENSTANWEISUNG"));

    assertRejected(
        vocabularyValueSql(insertDocument(insertLibrary(), "/uploads/b.pdf"), "DA"),
        "fk_document_metadata_values_vocabulary");
  }

  @Test
  void aCoreFieldValueIsPinnedToExactlyOneValueColumn() throws SQLException {
    UUID document = insertDocument(insertLibrary(), "/uploads/a.pdf");

    assertRejected(
        metadataValueSql(document, "document_type", "SET", "Vermerk", null, "MANUAL"),
        "chk_document_metadata_values_core_field_type");
    assertRejected(
        metadataValueSql(document, "title", "SET", null, null, "MANUAL"),
        "chk_document_metadata_values_one_value");
    // A date without its precision loses the only thing that makes it readable.
    assertRejected(
        metadataValueSql(document, "document_date", "SET", null, "2026-03-12", "MANUAL"),
        "chk_document_metadata_values_date_has_precision");
  }

  @Test
  void confidenceIsOnlyStorableWithADerivedOriginAndAnAutomaticValueCarriesItsVersion()
      throws SQLException {
    UUID document = insertDocument(insertLibrary(), "/uploads/a.pdf");

    assertRejected(
        "INSERT INTO document_metadata_values (id, document_id, field_key, text_value, origin,"
            + " extraction_version, confidence, created_at, updated_at) VALUES"
            + " (gen_random_uuid(), '"
            + document
            + "', 'title', 'Titel', 'DETERMINISTIC', 1, 0.9, now(), now())",
        "chk_document_metadata_values_confidence_only_derived");
    assertRejected(
        metadataValueSql(document, "title", "SET", "Titel", null, "DETERMINISTIC"),
        "chk_document_metadata_values_extraction_version");
  }

  @Test
  void notDeterminableIsOnlyStorableManuallyAndWithoutAValue() throws SQLException {
    UUID document = insertDocument(insertLibrary(), "/uploads/a.pdf");

    assertRejected(
        metadataValueSql(document, "title", "NOT_DETERMINABLE", "Titel", null, "MANUAL"),
        "chk_document_metadata_values_one_value");
    execute(metadataValueSql(document, "title", "NOT_DETERMINABLE", null, null, "MANUAL"));
  }

  @Test
  void oneMetadataValueRowPerDocumentAndFieldAndTheyDieWithTheDocument() throws SQLException {
    UUID document = insertDocument(insertLibrary(), "/uploads/a.pdf");
    execute(metadataValueSql(document, "title", "SET", "Erster Titel", null, "MANUAL"));

    assertRejected(
        metadataValueSql(document, "title", "SET", "Zweiter Titel", null, "MANUAL"),
        "uk_document_metadata_values_document_field");
    execute("DELETE FROM documents WHERE id = '" + document + "'");
    assertThat(countRows("document_metadata_values")).isZero();
  }

  /** The Aufnahmeregel of the specification, written into the database (#1071). */
  @Test
  void aLibraryMetadataFieldMustServeTheFilterOrTheContextPrefix() throws SQLException {
    UUID library = insertLibrary();

    assertRejected(
        libraryFieldSql(UUID.randomUUID(), library, "ohne_wirkung", false, false, null),
        "chk_library_metadata_fields_retrieval_effect");
    insertLibraryField(library, "fassung", true, false, null);
    insertLibraryField(library, "gremium", false, true, null);
  }

  @Test
  void atMostTwoLibraryFieldsCarryACitationPositionPerLibrary() throws SQLException {
    UUID library = insertLibrary();
    insertLibraryField(library, "fassung", true, false, 1);
    insertLibraryField(library, "gremium", true, false, 2);

    assertRejected(
        libraryFieldSql(UUID.randomUUID(), library, "projekt", true, false, 1),
        "uk_library_metadata_fields_citation_position");
    assertRejected(
        libraryFieldSql(UUID.randomUUID(), library, "phase", true, false, 3),
        "chk_library_metadata_fields_citation_position");
    insertLibraryField(insertLibrary(), "fassung", true, false, 1);
  }

  /**
   * "A document carrying a value the schema no longer has" is not a reachable state: the value is a
   * real foreign key, and removing a list entry a document still carries is blocked.
   */
  @Test
  void aLibraryValueStillCarriedByADocumentIsNotRemovable() throws SQLException {
    UUID library = insertLibrary();
    UUID field = insertLibraryField(library, "fassung", true, false, null);
    UUID value = insertLibraryFieldValue(field, "FASSUNG_2026");
    UUID document = insertDocument(library, "/uploads/a.pdf");

    // The lib: namespace and the field reference are pinned to each other.
    assertRejected(
        libraryValueSql(document, "fassung", field, value),
        "chk_document_metadata_values_library_field");
    execute(libraryValueSql(document, "lib:fassung", field, value));
    assertRejected(
        "DELETE FROM library_metadata_field_values WHERE id = '" + value + "'",
        "fk_document_metadata_values_library_value");
  }

  /**
   * A running schema change retires its subject: one mapping per list value, one deletion per
   * field. The kind decides what is representable, the subject cascades and the mapping target
   * restricts.
   */
  @Test
  void aSchemaChangeRunsOncePerSubjectAndItsKindDecidesWhatItNames() throws SQLException {
    UUID field = insertLibraryField(insertLibrary(), "fassung", true, false, null);
    UUID value = insertLibraryFieldValue(field, "ALT");
    UUID target = insertLibraryFieldValue(field, "NEU");
    execute(schemaChangeSql(field, value, target, "VALUE_REMAP"));
    execute(schemaChangeSql(field, null, null, "FIELD_DELETION"));

    assertRejected(
        schemaChangeSql(field, value, null, "VALUE_REMAP"),
        "uk_library_metadata_schema_changes_subject");
    assertRejected(
        schemaChangeSql(field, null, null, "FIELD_DELETION"),
        "uk_library_metadata_schema_changes_subject");
    assertRejected(
        schemaChangeSql(field, target, target, "VALUE_REMAP"),
        "chk_library_metadata_schema_changes_target");
    assertRejected(
        schemaChangeSql(field, null, null, "VALUE_REMAP"),
        "chk_library_metadata_schema_changes_subject");
    assertRejected(
        schemaChangeSql(field, target, null, "FIELD_DELETION"),
        "chk_library_metadata_schema_changes_subject");
    assertRejected(
        schemaChangeSql(field, null, null, "FIELD_RENAME"),
        "chk_library_metadata_schema_changes_kind");

    assertRejected(
        "DELETE FROM library_metadata_field_values WHERE id = '" + target + "'",
        "fk_library_metadata_schema_changes_target");
    execute("DELETE FROM library_metadata_field_values WHERE id = '" + value + "'");
    assertThat(countWhere("library_metadata_schema_changes", "change_kind = 'VALUE_REMAP'"))
        .isZero();
    execute("DELETE FROM library_metadata_fields WHERE id = '" + field + "'");
    assertThat(countRows("library_metadata_schema_changes")).isZero();
  }

  @Test
  void aDocumentTypeEndingNeedsAKnownDokumentartAndAPrefixOfAtLeastOneCharacter()
      throws SQLException {
    assertRejected(
        "INSERT INTO document_type_suffixes (code, suffix) VALUES ('RUNDSCHREIBEN', 'rundschreiben')",
        "fk_document_type_suffixes_code");
    assertRejected(
        "INSERT INTO document_type_suffixes (code, suffix, min_prefix_length) VALUES"
            + " ('VERMERK', 'merk', 0)",
        "chk_document_type_suffixes_min_prefix_length");

    execute("DELETE FROM document_type_vocabulary WHERE code = 'VERMERK'");
    assertThat(countWhere("document_type_suffixes", "code = 'VERMERK'")).isZero();
    assertThat(countWhere("document_type_suffix_exclusions", "code = 'VERMERK'")).isZero();
    assertThat(countWhere("document_type_synonyms", "code = 'VERMERK'")).isZero();
  }

  /**
   * A lower-cased abbreviation collides with everyday German words ("da"), which would empty an
   * otherwise unambiguous Dokumentart - so the delivered synonym list carries no short token. The
   * seed's content is reconciled in {@link DocumentTypeVocabularySeedReconciliationTest}.
   */
  @Test
  void noDeliveredSynonymIsShorterThanFourCharacters() throws SQLException {
    assertThat(countWhere("document_type_synonyms", "length(synonym) < 4")).isZero();
  }

  @Test
  void onlyTheTwoKnownRejectionReasonsAreStorableAndTheCounterRowStartsAtZero()
      throws SQLException {
    UUID library = insertLibrary();
    UUID document = insertDocument(library, "/uploads/a.pdf");
    execute(rejectionSql(library, document, "BELOW_THRESHOLD"));
    execute(rejectionSql(library, document, "OUTSIDE_VOCABULARY"));

    assertRejected(
        rejectionSql(library, document, "ACCEPTED"), "chk_metadata_model_rejections_reason");
    execute("INSERT INTO metadata_model_extraction_stats (library_id) VALUES ('" + library + "')");
    assertRejected(
        "INSERT INTO metadata_model_extraction_stats (library_id) VALUES ('" + library + "')",
        "metadata_model_extraction_stats_pkey");
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
    UUID library = insertLibrary();
    UUID document = insertDocument(library, "/uploads/a.pdf");
    execute(keywordSql(document, library, "abfallentsorgung"));

    assertRejected(
        keywordSql(document, library, "abfallentsorgung"), "uq_document_keywords_document_keyword");
    execute("DELETE FROM documents WHERE id = '" + document + "'");
    assertThat(countRows("document_keywords")).isZero();
  }

  // ---------------------------------------------------------------------------------------------
  // Model catalogue
  // ---------------------------------------------------------------------------------------------

  @Test
  void llmModelSeedMarkerStartsEmptyAndAcceptsExactlyOneRow() throws SQLException {
    assertThat(countRows("llm_model_seed_marker")).isZero();
    execute("INSERT INTO llm_model_seed_marker (id, seeded_at) VALUES (1, now())");

    assertRejected(
        "INSERT INTO llm_model_seed_marker (id, seeded_at) VALUES (2, now())",
        "chk_llm_model_seed_marker_singleton");
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  private static String libraryColumn(String expression, UUID library) {
    return "SELECT " + expression + " FROM knowledge_libraries WHERE id = '" + library + "'";
  }

  private static String librarySql(String sourceType, String sourceUrl, UUID id) {
    return "INSERT INTO knowledge_libraries (id, organization_id, source_type, source_url) VALUES ("
        + (id == null ? "gen_random_uuid()" : "'" + id + "'")
        + ", '"
        + SEEDED_ORGANIZATION_ID
        + "', '"
        + sourceType
        + "', "
        + quoted(sourceUrl)
        + ")";
  }

  /** An UPLOAD library of another organization, owned by an account of that organization. */
  private UUID insertLibraryOf(UUID organization) throws SQLException {
    UUID owner = insertUser(organization);
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO assets (id, asset_type, organization_id, name, owner_type, owner_user_id)"
            + " VALUES ('"
            + id
            + "', 'KNOWLEDGE_LIBRARY', '"
            + organization
            + "', 'Fremd', 'USER', '"
            + owner
            + "')");
    execute(
        "INSERT INTO knowledge_libraries (id, organization_id, source_type) VALUES ('"
            + id
            + "', '"
            + organization
            + "', 'UPLOAD')");
    return id;
  }

  private static String settings(UUID library, String jsonLiteral) {
    return "UPDATE knowledge_libraries SET source_settings = "
        + jsonLiteral
        + "::jsonb WHERE id = '"
        + library
        + "'";
  }

  private void insertDocumentWithSourceType(UUID library, String path, String sourceType)
      throws SQLException {
    execute(documentSourceTypeSql(library, path, sourceType));
  }

  private static String documentSourceTypeSql(UUID library, String path, String sourceType) {
    return "INSERT INTO documents (id, file_name, file_path, status, source_type, library_id,"
        + " organization_id) VALUES (gen_random_uuid(), 'report.pdf', '"
        + path
        + "', 'INDEXED', '"
        + sourceType
        + "', '"
        + library
        + "', '"
        + SEEDED_ORGANIZATION_ID
        + "')";
  }

  /** An UPLOAD-sourced row that may hang off a parent document and carry a dedup checksum. */
  private UUID insertAttachment(UUID library, String path, UUID parent, String checksum)
      throws SQLException {
    UUID id = UUID.randomUUID();
    execute(attachmentSql(id, library, path, parent, checksum));
    return id;
  }

  private static String attachmentSql(
      UUID id, UUID library, String path, UUID parent, String checksum) {
    return "INSERT INTO documents (id, file_name, file_path, status, source_type, library_id,"
        + " organization_id, parent_document_id, checksum) VALUES ('"
        + id
        + "', 'anlage.pdf', '"
        + path
        + "', 'INDEXED', 'UPLOAD', '"
        + library
        + "', '"
        + SEEDED_ORGANIZATION_ID
        + "', "
        + quoted(parent)
        + ", "
        + quoted(checksum)
        + ")";
  }

  private static String indexingJobSql(
      UUID library, String status, String runMode, String triggeredBy) {
    return "INSERT INTO indexing_jobs (id, status, run_mode, triggered_by, last_progress_at,"
        + " library_id, organization_id) VALUES (gen_random_uuid(), '"
        + status
        + "', '"
        + runMode
        + "', '"
        + triggeredBy
        + "', now(), '"
        + library
        + "', '"
        + SEEDED_ORGANIZATION_ID
        + "')";
  }

  private static String sourceSyncStateSql(UUID library) {
    return "INSERT INTO source_sync_state (id, library_id, updated_at) VALUES (gen_random_uuid(), '"
        + library
        + "', now())";
  }

  private static String chunkFullTextSql(UUID chunkId, String content) {
    return "INSERT INTO chunk_full_text (chunk_id, document_id, library_id, content_tsv) VALUES ('"
        + chunkId
        + "', gen_random_uuid(), gen_random_uuid(), to_tsvector('german', '"
        + content
        + "'))";
  }

  private static String vocabularyValueSql(UUID document, String code) {
    return "INSERT INTO document_metadata_values (id, document_id, field_key, vocabulary_code,"
        + " origin, extraction_version, created_at, updated_at) VALUES (gen_random_uuid(), '"
        + document
        + "', 'document_type', '"
        + code
        + "', 'DETERMINISTIC', 1, now(), now())";
  }

  /** Never sets {@code extraction_version} - only a MANUAL value may omit it. */
  private static String metadataValueSql(
      UUID document,
      String fieldKey,
      String valueState,
      String textValue,
      String isoDate,
      String origin) {
    return "INSERT INTO document_metadata_values (id, document_id, field_key, value_state,"
        + " text_value, date_value, origin, created_at, updated_at) VALUES (gen_random_uuid(), '"
        + document
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
        + "', now(), now())";
  }

  private UUID insertLibraryField(
      UUID library, String fieldKey, boolean filter, boolean contextPrefix, Integer citation)
      throws SQLException {
    UUID id = UUID.randomUUID();
    execute(libraryFieldSql(id, library, fieldKey, filter, contextPrefix, citation));
    return id;
  }

  private static String libraryFieldSql(
      UUID id,
      UUID library,
      String fieldKey,
      boolean filter,
      boolean contextPrefix,
      Integer citation) {
    return "INSERT INTO library_metadata_fields (id, library_id, field_key, label, field_type,"
        + " filter_enabled, context_prefix_enabled, citation_enabled, citation_position,"
        + " sort_order, created_at, updated_at) VALUES ('"
        + id
        + "', '"
        + library
        + "', '"
        + fieldKey
        + "', 'Label', 'SELECT', "
        + filter
        + ", "
        + contextPrefix
        + ", "
        + (citation != null)
        + ", "
        + (citation == null ? "NULL" : citation)
        + ", 10, now(), now())";
  }

  private UUID insertLibraryFieldValue(UUID field, String code) throws SQLException {
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO library_metadata_field_values (id, field_id, code, label, sort_order) VALUES"
            + " ('"
            + id
            + "', '"
            + field
            + "', '"
            + code
            + "', 'Label', 10)");
    return id;
  }

  private static String libraryValueSql(UUID document, String fieldKey, UUID field, UUID value) {
    return "INSERT INTO document_metadata_values (id, document_id, field_key, text_value,"
        + " library_field_id, library_value_id, origin, created_at, updated_at) VALUES"
        + " (gen_random_uuid(), '"
        + document
        + "', '"
        + fieldKey
        + "', 'Fassung 2026', '"
        + field
        + "', '"
        + value
        + "', 'MANUAL', now(), now())";
  }

  private static String schemaChangeSql(UUID field, UUID value, UUID target, String kind) {
    return "INSERT INTO library_metadata_schema_changes (id, field_id, value_id, target_value_id,"
        + " change_kind, correlation_ref) VALUES (gen_random_uuid(), '"
        + field
        + "', "
        + quoted(value)
        + ", "
        + quoted(target)
        + ", '"
        + kind
        + "', 'corr')";
  }

  private static String rejectionSql(UUID library, UUID document, String reason) {
    return "INSERT INTO metadata_model_rejections (id, library_id, document_id, field_key,"
        + " proposed_value, confidence, reason) VALUES (gen_random_uuid(), '"
        + library
        + "', '"
        + document
        + "', 'document_type', 'Rundschreiben', 0.4, '"
        + reason
        + "')";
  }

  private static String keywordSql(UUID document, UUID library, String keyword) {
    return "INSERT INTO document_keywords (id, document_id, library_id, keyword) VALUES"
        + " (gen_random_uuid(), '"
        + document
        + "', '"
        + library
        + "', '"
        + keyword
        + "')";
  }
}
