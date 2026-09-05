package io.opaa.indexing.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.DatePrecision;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.MetadataOrigin;
import io.opaa.api.types.SystemRole;
import io.opaa.common.NotFoundException;
import io.opaa.indexing.ChecksumService;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIndexingIntegrationTest;
import io.opaa.test.OpaaIndexingTestDirectory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.message.BodyPartBuilder;
import org.apache.james.mime4j.message.DefaultMessageWriter;
import org.apache.james.mime4j.message.MultipartBuilder;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The deterministic backfill over an Altbestand: documents are indexed normally, then reset to the
 * state of a bestand indexed before ADR-0024 (no extraction version, no values, no chunk keys), and
 * the backfill has to restore all three in batches from the original files - without touching a
 * single chunk, idempotently, and past a document it cannot read.
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
  @Autowired private VectorStore vectorStore;
  @Autowired private ChecksumService checksumService;

  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() throws IOException {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store, chunk_full_text");
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

  /**
   * A raised {@link CoreMetadataExtractor#EXTRACTION_VERSION} is what draws an already extracted
   * bestand back into the selection - the mechanism the new Dokumentart sources rely on to reach
   * documents extracted by an older version.
   */
  @Test
  void aDocumentBelowTheCurrentExtractionVersionIsSelectedAgain() throws IOException {
    indexAltbestand();
    backfillService.backfillBatch(Organization.DEFAULT_ID, library.getId(), 10);
    assertThat(progress().isComplete()).isTrue();

    jdbcTemplate.update(
        "UPDATE documents SET metadata_extraction_version = ?",
        CoreMetadataExtractor.EXTRACTION_VERSION - 1);

    MetadataBackfillProgress outdated = progress();
    assertThat(outdated.pendingDocuments()).isEqualTo(3);
    assertThat(outdated.currentDocuments()).isZero();
    assertThat(outdated.isComplete()).isFalse();

    MetadataBackfillResult rerun =
        backfillService.backfillBatch(Organization.DEFAULT_ID, library.getId(), 10);

    assertThat(rerun.processedDocuments()).isEqualTo(3);
    assertThat(progress().isComplete()).isTrue();
    assertThat(documentRepository.findAll())
        .allSatisfy(
            document ->
                assertThat(document.getMetadataExtractionVersion())
                    .isEqualTo(CoreMetadataExtractor.EXTRACTION_VERSION));
  }

  /**
   * the Version-2-Bestand carries wrong DETERMINISTIC Dokumentart values from the old head rule (a
   * label line, a quotation). The rerun has to remove them where nothing is left to extract, and to
   * leave a manual correction untouched.
   */
  @Test
  void aWrongDeterministicValueOfAnOlderVersionIsRemovedWhileAManualOneSurvives()
      throws IOException {
    Path leistung = classTempDir.resolve("13_fabrikneues-fahrzeug-anmelden.md");
    Files.writeString(
        leistung,
        """
        # Fabrikneues Fahrzeug anmelden

        **Formular:** RF-KFZ-001

        Die Zulassungsstelle nimmt den Antrag persoenlich entgegen.
        """);
    Path faq = classTempDir.resolve("15_faq-ausweisbeantragung.md");
    Files.writeString(
        faq,
        """
        # Haeufige Fragen zur Ausweisbeantragung

        Termine werden nach der Dienstanweisung zur Terminvergabe vergeben.
        """);
    for (Path file : List.of(leistung, faq)) {
      assertThat(fileProcessingService.processFile(file, library))
          .isEqualTo(FileProcessingResult.PROCESSED);
    }
    Document leistungDocument = documentNamed("13_fabrikneues-fahrzeug-anmelden.md");
    Document faqDocument = documentNamed("15_faq-ausweisbeantragung.md");
    jdbcTemplate.update("DELETE FROM document_metadata_values WHERE field_key = 'document_type'");
    valueRepository.save(
        DocumentMetadataValue.deterministic(
                leistungDocument.getId(),
                CoreMetadataField.DOCUMENT_TYPE,
                CoreMetadataExtractor.EXTRACTION_VERSION - 1)
            .assignVocabularyCode("FORMULAR"));
    valueRepository.save(
        DocumentMetadataValue.manual(faqDocument.getId(), CoreMetadataField.DOCUMENT_TYPE, null)
            .assignVocabularyCode("VERMERK"));
    valueRepository.flush();
    jdbcTemplate.update(
        "UPDATE documents SET metadata_extraction_version = ?",
        CoreMetadataExtractor.EXTRACTION_VERSION - 1);

    MetadataBackfillResult rerun =
        backfillService.backfillBatch(Organization.DEFAULT_ID, library.getId(), 10);

    assertThat(rerun.processedDocuments()).isEqualTo(2);
    assertThat(documentMetadataService.coreMetadataFor(leistungDocument.getId()).documentTypeCode())
        .isNull();
    assertThat(valueRepository.findByDocumentId(leistungDocument.getId()))
        .noneMatch(value -> value.getFieldKey().equals(CoreMetadataField.DOCUMENT_TYPE.key()));
    assertThat(chunkMetadata(leistungDocument.getId()))
        .isNotEmpty()
        .allSatisfy(metadata -> assertThat(metadata).doesNotContainKey("doc_type"));
    assertThat(documentMetadataService.coreMetadataFor(faqDocument.getId()).documentTypeCode())
        .isEqualTo("VERMERK");
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
    // The stored file_name is the headline, not a file name: the backfill must read no
    // naming convention out of it, exactly as the ingest does not.
    assertThat(core.documentTypeCode()).isNull();
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
    // Still pending, but the display can tell why: it waits for its connector run, and no further
    // backfill call will change that.
    MetadataBackfillProgress progress = progress();
    assertThat(progress.pendingDocuments()).isEqualTo(1);
    assertThat(progress.awaitingConnectorRunDocuments()).isEqualTo(1);

    assertThat(
            backfillService.backfillBatch(Organization.DEFAULT_ID, library.getId(), 10).isEmpty())
        .as("a marked remote document is not marked again on every call")
        .isTrue();
  }

  /**
   * The chunks were cut from the bytes read at indexing time. A file replaced since then would put
   * the core fields of a different text onto those chunks - the same rule the attachment path
   * applies via its checksum - so the document is skipped and left to its next connector run.
   */
  @Test
  void aFileChangedSinceIndexingIsSkippedSoNewFieldsNeverLandOnOldChunks() throws IOException {
    indexAltbestand();
    Document changed = documentNamed("Protokoll_Sitzung_2025-11.pdf");
    writePdf(Path.of(changed.getFilePath()), "Voellig anderer Inhalt seit dem Indexlauf");

    MetadataBackfillResult result =
        backfillService.backfillBatch(Organization.DEFAULT_ID, library.getId(), 10);

    assertThat(result.processedDocuments()).isEqualTo(2);
    assertThat(result.skippedDocuments()).isEqualTo(1);
    assertThat(valueRepository.findByDocumentId(changed.getId())).isEmpty();
    assertThat(
            documentRepository
                .findById(changed.getId())
                .orElseThrow()
                .getMetadataExtractionVersion())
        .isNull();
    assertThat(chunkMetadata(changed.getId()))
        .isNotEmpty()
        .allSatisfy(metadata -> assertThat(metadata).doesNotContainKey("doc_type"));
  }

  /**
   * An attachment document has no file of its own - its bytes are re-extracted from the parent mail
   * along the chain, and the core fields come from that re-extracted file (here: the attachment's
   * own file-name convention), while its chunks stay exactly as they were.
   */
  @Test
  void aLocalMailAttachmentIsReextractedFromItsParentMailWithoutTouchingItsChunks()
      throws Exception {
    byte[] pdf = pdfBytes("Regelung zum Arbeiten von zu Hause.");
    String attachmentName = "2026-03-12_Dienstanweisung_Homeoffice.pdf";
    Message message =
        Message.Builder.of()
            .setSubject("Neue Dienstanweisung")
            .setFrom("Personalamt <personalamt@example.org>")
            .setTo("Alle <alle@example.org>")
            .setBody(
                MultipartBuilder.create("mixed")
                    .addTextPart("Anbei die neue Dienstanweisung.", StandardCharsets.UTF_8)
                    .addBodyPart(
                        BodyPartBuilder.create()
                            .setBody(pdf, "application/pdf")
                            .setContentDisposition("attachment", attachmentName))
                    .build())
            .build();
    Path emlFile = classTempDir.resolve("dienstanweisung.eml");
    Files.write(emlFile, DefaultMessageWriter.asBytes(message));
    Document mail =
        persistedIndexedDocument(
            "dienstanweisung.eml",
            emlFile.toAbsolutePath().toString(),
            DocumentSourceType.FILESYSTEM,
            checksumService.computeSha256(emlFile),
            null);
    Document attachment =
        persistedIndexedDocument(
            attachmentName,
            emlFile.toAbsolutePath() + "/0/" + attachmentName,
            DocumentSourceType.FILESYSTEM,
            checksumService.computeSha256(pdf),
            mail.getId());
    seedChunk(attachment.getId(), "alter Anhang-Chunk");
    List<UUID> chunkIdsBefore = allChunkIds();

    MetadataBackfillResult result =
        backfillService.backfillBatch(Organization.DEFAULT_ID, library.getId(), 10);

    assertThat(result.processedDocuments()).isEqualTo(2);
    assertThat(result.skippedDocuments()).isZero();
    CoreMetadata core = documentMetadataService.coreMetadataFor(attachment.getId());
    assertThat(core.documentTypeCode()).isEqualTo("DIENSTANWEISUNG");
    assertThat(core.documentDate()).isEqualTo(LocalDate.of(2026, 3, 12));
    assertThat(allChunkIds()).containsExactlyElementsOf(chunkIdsBefore);
    assertThat(chunkMetadata(attachment.getId()))
        .hasSize(1)
        .allSatisfy(metadata -> assertThat(metadata).containsEntry("doc_type", "DIENSTANWEISUNG"));
    assertThat(progress().isComplete()).isTrue();
  }

  /** A remote attachment marks its whole chain, root included, and then drains. */
  @Test
  void aRemoteAttachmentMarksItsWholeParentChainForTheNextRunAndDropsOutOfTheSelection() {
    UUID mailId = UUID.randomUUID();
    insertRemoteDocument(
        mailId,
        DocumentSourceType.HTTP_DIRECTORY,
        "post.eml",
        "https://files.example/post.eml",
        "Mon, 01 Sep 2026 10:00:00 GMT",
        null);
    UUID attachmentId = UUID.randomUUID();
    insertRemoteDocument(
        attachmentId,
        DocumentSourceType.HTTP_DIRECTORY,
        "anlage.pdf",
        "https://files.example/post.eml/0/anlage.pdf",
        null,
        mailId);

    MetadataBackfillResult first =
        backfillService.backfillBatch(Organization.DEFAULT_ID, library.getId(), 10);

    assertThat(first.markedForNextRun()).isEqualTo(2);
    assertThat(first.processedDocuments()).isZero();
    for (UUID id : List.of(mailId, attachmentId)) {
      Document reloaded = documentRepository.findById(id).orElseThrow();
      assertThat(reloaded.getChecksum()).isNull();
      assertThat(reloaded.getLastModifiedRemote()).isNull();
    }
    MetadataBackfillProgress progress = progress();
    assertThat(progress.pendingDocuments()).isEqualTo(2);
    assertThat(progress.awaitingConnectorRunDocuments()).isEqualTo(2);
    assertThat(
            backfillService.backfillBatch(Organization.DEFAULT_ID, library.getId(), 10).isEmpty())
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
    insertRemoteDocument(documentId, sourceType, fileName, filePath, lastModifiedRemote, null);
  }

  private void insertRemoteDocument(
      UUID documentId,
      DocumentSourceType sourceType,
      String fileName,
      String filePath,
      String lastModifiedRemote,
      UUID parentDocumentId) {
    jdbcTemplate.update(
        "INSERT INTO documents (id, file_name, file_path, content_type, file_size, chunk_count,"
            + " indexed_at, checksum, last_modified_remote, status, source_type, library_id,"
            + " organization_id, parent_document_id, created_at) VALUES (?, ?, ?, 'text/html',"
            + " 1024, 1, now(), ?, ?, 'INDEXED', ?, ?, ?, ?, now())",
        documentId,
        fileName,
        filePath,
        "checksum-" + documentId,
        lastModifiedRemote,
        sourceType.name(),
        library.getId(),
        Organization.DEFAULT_ID,
        parentDocumentId);
  }

  private Document persistedIndexedDocument(
      String fileName,
      String filePath,
      DocumentSourceType sourceType,
      String checksum,
      UUID parentDocumentId) {
    Document document =
        new Document(fileName, filePath, "application/octet-stream", 1L, sourceType);
    document.setLibraryId(library.getId());
    document.setOrganizationId(Organization.DEFAULT_ID);
    document.setChecksum(checksum);
    document.setParentDocumentId(parentDocumentId);
    document.setStatus(DocumentStatus.INDEXED);
    return documentRepository.save(document);
  }

  private void seedChunk(UUID documentId, String text) {
    Map<String, Object> metadata = new HashMap<>();
    metadata.put(VectorChunkStore.DOCUMENT_ID_METADATA_KEY, documentId.toString());
    metadata.put(VectorChunkStore.LIBRARY_ID_METADATA_KEY, library.getId().toString());
    metadata.put("organization_id", Organization.DEFAULT_ID.toString());
    vectorStore.add(List.of(new org.springframework.ai.document.Document(text, metadata)));
  }

  private byte[] pdfBytes(String text) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (PDDocument pdf = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.A4);
      pdf.addPage(page);
      try (PDPageContentStream stream = new PDPageContentStream(pdf, page)) {
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        stream.newLineAtOffset(50, 700);
        stream.showText(text);
        stream.endText();
      }
      pdf.save(out);
    }
    return out.toByteArray();
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
