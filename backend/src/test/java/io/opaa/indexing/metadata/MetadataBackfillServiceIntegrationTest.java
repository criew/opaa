package io.opaa.indexing.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.DatePrecision;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.MetadataOrigin;
import io.opaa.api.types.SystemRole;
import io.opaa.common.NotFoundException;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIndexingIntegrationTest;
import io.opaa.test.OpaaIndexingTestDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The deterministic backfill over an Altbestand (#1067): documents are indexed normally, then reset
 * to the state of a bestand indexed before ADR-0024 (no extraction version, no values, no chunk
 * keys), and the backfill has to restore all three in batches from the original files - without
 * touching a single chunk, idempotently, and past a document it cannot read.
 */
@OpaaIndexingIntegrationTest
class MetadataBackfillServiceIntegrationTest {

  private static final Path classTempDir =
      OpaaIndexingTestDirectory.subdirectory("metadata-backfill");

  @Autowired private MetadataBackfillService backfillService;
  @Autowired private FileProcessingService fileProcessingService;
  @Autowired private DocumentMetadataService documentMetadataService;
  @Autowired private DocumentMetadataValueRepository valueRepository;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() throws IOException {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store, chunk_full_text, chunk_full_text_skip");
    jdbcTemplate.update("DELETE FROM documents");
    jdbcTemplate.update(
        "DELETE FROM knowledge_libraries WHERE owner_user_id IN (SELECT id FROM users WHERE"
            + " email = 'metadata-backfill-it@example.com')");
    jdbcTemplate.update("DELETE FROM users WHERE email = 'metadata-backfill-it@example.com'");
    UUID userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', 'metadata-backfill-it@example.com',"
            + " 'Metadata Backfill IT User', now(), ?, ?)",
        userId,
        "metadata-backfill-it-" + userId,
        SystemRole.SYSTEM_ADMIN.name(),
        Organization.DEFAULT_ID);
    // A FILESYSTEM library: sourcePath is what a document's file must resolve underneath before
    // the backfill may read it again (ADR-0018, Entscheidung 6).
    library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Altbestand",
                null,
                userId,
                LibraryVisibility.PRIVATE,
                false,
                DocumentSourceType.FILESYSTEM,
                classTempDir.toString(),
                null,
                null,
                null,
                false));
    Files.createDirectories(classTempDir);
    try (var files = Files.list(classTempDir)) {
      for (Path file : files.toList()) {
        Files.deleteIfExists(file);
      }
    }
  }

  @Test
  void anAltbestandIsBackfilledInBatchesFromTheOriginalFilesWithoutTouchingAChunk()
      throws IOException {
    indexAltbestand();
    List<UUID> chunkIdsBefore = allChunkIds();
    MetadataBackfillProgress before = progress();
    assertThat(before.totalDocuments()).isEqualTo(3);
    assertThat(before.pendingDocuments()).isEqualTo(3);
    assertThat(before.currentDocuments()).isZero();
    assertThat(before.filledDocumentsByField().get(CoreMetadataField.TITLE)).isZero();

    MetadataBackfillResult first =
        backfillService.backfillBatch(Organization.DEFAULT_ID, library.getId(), 2);
    assertThat(first.processedDocuments()).isEqualTo(2);
    assertThat(first.isEmpty()).isFalse();
    // The mixed state is a defined, permitted operating state: the search keeps every chunk it had,
    // the remaining document still carries its old (empty) fields and is visible as pending.
    assertThat(allChunkIds()).containsExactlyElementsOf(chunkIdsBefore);
    MetadataBackfillProgress midway = progress();
    assertThat(midway.currentDocuments()).isEqualTo(2);
    assertThat(midway.pendingDocuments()).isEqualTo(1);
    assertThat(midway.isComplete()).isFalse();

    MetadataBackfillResult second =
        backfillService.backfillBatch(Organization.DEFAULT_ID, library.getId(), 2);
    assertThat(second.processedDocuments()).isEqualTo(1);
    assertThat(backfillService.backfillBatch(Organization.DEFAULT_ID, library.getId(), 2).isEmpty())
        .as("a drained run reports nothing left to do")
        .isTrue();

    MetadataBackfillProgress after = progress();
    assertThat(after.isComplete()).isTrue();
    assertThat(after.currentDocuments()).isEqualTo(3);
    assertThat(after.filledDocumentsByField())
        .containsEntry(CoreMetadataField.TITLE, 3L)
        .containsEntry(CoreMetadataField.DOCUMENT_TYPE, 3L)
        .containsEntry(CoreMetadataField.DOCUMENT_DATE, 3L);
    assertThat(after.filledShare(CoreMetadataField.DOCUMENT_TYPE)).isEqualTo(1.0d);

    // The values came from the original files - the file-name convention and the frontmatter,
    // neither of which is in the chunk text - and they carry the extraction version again.
    Document dienstanweisung = documentNamed("2026-03-12_Dienstanweisung_IT-Nutzung.pdf");
    assertThat(dienstanweisung.getMetadataExtractionVersion())
        .isEqualTo(CoreMetadataExtractor.EXTRACTION_VERSION);
    CoreMetadata core = documentMetadataService.coreMetadataFor(dienstanweisung.getId());
    assertThat(core.documentTypeCode()).isEqualTo("DIENSTANWEISUNG");
    assertThat(core.documentDate()).isEqualTo(LocalDate.of(2026, 3, 12));
    assertThat(core.documentDatePrecision()).isEqualTo(DatePrecision.DAY);
    assertThat(core.documentTypeOrigin()).isEqualTo(MetadataOrigin.DETERMINISTIC);
    CoreMetadata satzung =
        documentMetadataService.coreMetadataFor(documentNamed("satzung.md").getId());
    assertThat(satzung.title()).isEqualTo("Sozialgebührenbefreiungssatzung");
    assertThat(satzung.documentTypeCode()).isEqualTo("SATZUNG_ORDNUNG");
    assertThat(allChunkIds()).containsExactlyElementsOf(chunkIdsBefore);
    assertThat(chunkMetadata(dienstanweisung.getId()))
        .isNotEmpty()
        .allSatisfy(
            metadata -> {
              assertThat(metadata).containsEntry("doc_type", "DIENSTANWEISUNG");
              assertThat(metadata).containsEntry("doc_date", "2026-03-12");
              assertThat(metadata).containsEntry("doc_date_precision", "DAY");
            });
  }

  @Test
  void aSecondRunOverProcessedDocumentsChangesNothing() throws IOException {
    indexAltbestand();
    backfillService.backfillBatch(Organization.DEFAULT_ID, library.getId(), 10);
    List<Object[]> rowsBefore = valueRows();
    List<Map<String, Object>> chunksBefore = allChunkMetadata();

    MetadataBackfillResult again =
        backfillService.backfillBatch(Organization.DEFAULT_ID, library.getId(), 10);

    assertThat(again.isEmpty()).isTrue();
    assertThat(again.skippedDocuments()).isZero();
    assertThat(valueRows()).usingRecursiveComparison().isEqualTo(rowsBefore);
    assertThat(allChunkMetadata()).isEqualTo(chunksBefore);
  }

  @Test
  void aManuallySetValueIsNeverOverwrittenByTheBackfill() throws IOException {
    indexAltbestand();
    Document document = documentNamed("2026-03-12_Dienstanweisung_IT-Nutzung.pdf");
    valueRepository.save(
        DocumentMetadataValue.manual(document.getId(), CoreMetadataField.DOCUMENT_TYPE, null)
            .assignVocabularyCode("VERMERK"));
    valueRepository.flush();

    backfillService.backfillBatch(Organization.DEFAULT_ID, library.getId(), 10);

    CoreMetadata core = documentMetadataService.coreMetadataFor(document.getId());
    assertThat(core.documentTypeCode()).isEqualTo("VERMERK");
    assertThat(core.documentTypeOrigin()).isEqualTo(MetadataOrigin.MANUAL);
    // The deterministic fields around it are filled, and the chunks carry the manual value.
    assertThat(core.documentDate()).isEqualTo(LocalDate.of(2026, 3, 12));
    assertThat(chunkMetadata(document.getId()))
        .allSatisfy(metadata -> assertThat(metadata).containsEntry("doc_type", "VERMERK"));
    assertThat(progress().isComplete()).isTrue();
  }

  @Test
  void aDocumentWhoseFileCannotBeReadIsSkippedAndTheRunStillEnds() throws IOException {
    indexAltbestand();
    Document vanished = documentNamed("Protokoll_Sitzung_2025-11.pdf");
    Files.delete(Path.of(vanished.getFilePath()));

    MetadataBackfillResult first =
        backfillService.backfillBatch(Organization.DEFAULT_ID, library.getId(), 10);
    assertThat(first.processedDocuments()).isEqualTo(2);
    assertThat(first.skippedDocuments()).isEqualTo(1);
    // The skipped document is left exactly as it was - no version, no values, chunks intact - and
    // stays visible as pending; the next call retries it and, still unable, reports done.
    assertThat(
            documentRepository
                .findById(vanished.getId())
                .orElseThrow()
                .getMetadataExtractionVersion())
        .isNull();
    assertThat(valueRepository.findByDocumentId(vanished.getId())).isEmpty();
    assertThat(chunkMetadata(vanished.getId())).isNotEmpty();
    MetadataBackfillProgress progress = progress();
    assertThat(progress.pendingDocuments()).isEqualTo(1);
    assertThat(progress.lastSkippedDocuments()).isEqualTo(1);
    assertThat(progress.isComplete()).isFalse();

    MetadataBackfillResult second =
        backfillService.backfillBatch(Organization.DEFAULT_ID, library.getId(), 10);
    assertThat(second.isEmpty()).isTrue();
    assertThat(second.skippedDocuments()).isEqualTo(1);
  }

  @Test
  void anRssEntryIsReextractedFromItsStoredHeadlineAndPublicationDateWithoutADownload() {
    UUID entryId = UUID.randomUUID();
    insertRemoteDocument(
        entryId,
        DocumentSourceType.RSS_FEED,
        "Gebührensatzung tritt in Kraft",
        "https://feed.example/eintrag-1",
        "2026-03-12T10:00:00Z");

    MetadataBackfillResult result =
        backfillService.backfillBatch(Organization.DEFAULT_ID, library.getId(), 10);

    assertThat(result.processedDocuments()).isEqualTo(1);
    assertThat(result.markedForNextRun()).isZero();
    CoreMetadata core = documentMetadataService.coreMetadataFor(entryId);
    assertThat(core.title()).isEqualTo("Gebührensatzung tritt in Kraft");
    assertThat(core.documentDate()).isEqualTo(LocalDate.of(2026, 3, 12));
    assertThat(core.documentDatePrecision()).isEqualTo(DatePrecision.DAY);
    assertThat(documentRepository.findById(entryId).orElseThrow().getMetadataExtractionVersion())
        .isEqualTo(CoreMetadataExtractor.EXTRACTION_VERSION);
  }

  @Test
  void aRemoteHttpDocumentIsMarkedForItsNextConnectorRunAndThenDropsOutOfTheSelection() {
    UUID remoteId = UUID.randomUUID();
    insertRemoteDocument(
        remoteId,
        DocumentSourceType.HTTP_DIRECTORY,
        "2025-06-01_Vermerk_Haushalt.pdf",
        "https://files.example/2025-06-01_Vermerk_Haushalt.pdf",
        "Sun, 01 Jun 2025 10:00:00 GMT");

    MetadataBackfillResult first =
        backfillService.backfillBatch(Organization.DEFAULT_ID, library.getId(), 10);
    assertThat(first.markedForNextRun()).isEqualTo(1);
    assertThat(first.processedDocuments()).isZero();
    Document marked = documentRepository.findById(remoteId).orElseThrow();
    // Both change markers cleared, so the next connector run re-reads (and re-extracts) it; until
    // then it stays pending in the status - the display does not beautify it.
    assertThat(marked.getChecksum()).isNull();
    assertThat(marked.getLastModifiedRemote()).isNull();
    assertThat(marked.getMetadataExtractionVersion()).isNull();
    assertThat(progress().pendingDocuments()).isEqualTo(1);

    assertThat(
            backfillService.backfillBatch(Organization.DEFAULT_ID, library.getId(), 10).isEmpty())
        .as("a marked remote document is not marked again on every call")
        .isTrue();
  }

  @Test
  void aLibraryOfAnotherOrganizationIsAbsentNotForbidden() {
    assertThatThrownBy(() -> backfillService.backfillBatch(UUID.randomUUID(), library.getId(), 10))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Bibliothek nicht gefunden");
  }

  /**
   * Three documents indexed with the current extraction, then reset to the shape of a bestand
   * indexed before ADR-0024: no extraction version, no value rows, no filterable chunk keys.
   */
  private void indexAltbestand() throws IOException {
    Path dienstanweisung = classTempDir.resolve("2026-03-12_Dienstanweisung_IT-Nutzung.pdf");
    writePdf(dienstanweisung, "Dienstanweisung zur IT-Nutzung");
    Path protokoll = classTempDir.resolve("Protokoll_Sitzung_2025-11.pdf");
    writePdf(protokoll, null);
    Path satzung = classTempDir.resolve("satzung.md");
    Files.writeString(
        satzung,
        """
        ---
        titel: "Sozialgebührenbefreiungssatzung"
        dokumentart: "satzung"
        stand_datum: "2024-01-01"
        ---

        # Sozialgebührenbefreiungssatzung

        Diese Satzung regelt die Befreiung von Gebühren.
        """);
    for (Path file : List.of(dienstanweisung, protokoll, satzung)) {
      assertThat(fileProcessingService.processFile(file, library))
          .isEqualTo(FileProcessingResult.PROCESSED);
    }
    jdbcTemplate.update("DELETE FROM document_metadata_values");
    jdbcTemplate.update("UPDATE documents SET metadata_extraction_version = NULL");
    jdbcTemplate.update(
        "UPDATE vector_store SET metadata = (metadata::jsonb - 'doc_type' - 'doc_date' -"
            + " 'doc_date_precision')::json");
    assertThat(allChunkMetadata())
        .isNotEmpty()
        .allSatisfy(metadata -> assertThat(metadata).doesNotContainKey("doc_type"));
  }

  private void insertRemoteDocument(
      UUID documentId,
      DocumentSourceType sourceType,
      String fileName,
      String filePath,
      String lastModifiedRemote) {
    jdbcTemplate.update(
        "INSERT INTO documents (id, file_name, file_path, content_type, file_size, chunk_count,"
            + " indexed_at, checksum, last_modified_remote, status, source_type, library_id,"
            + " organization_id, created_at) VALUES (?, ?, ?, 'text/html', 1024, 1, now(), ?, ?,"
            + " 'INDEXED', ?, ?, ?, now())",
        documentId,
        fileName,
        filePath,
        "checksum-" + documentId,
        lastModifiedRemote,
        sourceType.name(),
        library.getId(),
        Organization.DEFAULT_ID);
  }

  private MetadataBackfillProgress progress() {
    return backfillService
        .progressForLibraries(List.of(library.getId()))
        .getOrDefault(library.getId(), MetadataBackfillProgress.empty(library.getId()));
  }

  private Document documentNamed(String fileName) {
    return documentRepository.findAll().stream()
        .filter(document -> fileName.equals(document.getFileName()))
        .findFirst()
        .orElseThrow();
  }

  private List<Object[]> valueRows() {
    return jdbcTemplate.query(
        "SELECT id, document_id, field_key, origin, extraction_version, text_value,"
            + " vocabulary_code, date_value, updated_at FROM document_metadata_values ORDER BY"
            + " document_id, field_key",
        (rs, i) ->
            new Object[] {
              rs.getObject("id"),
              rs.getObject("document_id"),
              rs.getString("field_key"),
              rs.getString("origin"),
              rs.getInt("extraction_version"),
              rs.getString("text_value"),
              rs.getString("vocabulary_code"),
              rs.getDate("date_value"),
              rs.getTimestamp("updated_at")
            });
  }

  private List<UUID> allChunkIds() {
    return jdbcTemplate.query(
        "SELECT id FROM vector_store ORDER BY id", (rs, i) -> UUID.fromString(rs.getString("id")));
  }

  private List<Map<String, Object>> allChunkMetadata() {
    return jdbcTemplate.query(
        "SELECT metadata::text AS metadata FROM vector_store ORDER BY id",
        (rs, i) -> parseJson(rs.getString("metadata")));
  }

  private List<Map<String, Object>> chunkMetadata(UUID documentId) {
    return jdbcTemplate.query(
        "SELECT metadata::text AS metadata FROM vector_store WHERE metadata->>'document_id' = ?",
        (rs, i) -> parseJson(rs.getString("metadata")),
        documentId.toString());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseJson(String json) {
    return new tools.jackson.databind.ObjectMapper().readValue(json, Map.class);
  }

  private static void writePdf(Path file, String title) throws IOException {
    try (PDDocument doc = new PDDocument()) {
      for (String text :
          List.of(
              "Diese Anweisung regelt die Nutzung der IT.",
              "Passwoerter sind vertraulich zu behandeln.",
              "Private Nutzung ist untersagt.")) {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
          content.beginText();
          content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
          content.newLineAtOffset(50, 700);
          content.showText(text);
          content.endText();
        }
      }
      if (title != null) {
        doc.getDocumentInformation().setTitle(title);
      }
      doc.save(file.toFile());
    }
  }
}
