package io.opaa.indexing.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DatePrecision;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.MetadataOrigin;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.library.AssetGrant;
import io.opaa.library.AssetGrantRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIndexingIntegrationTest;
import io.opaa.test.OpaaIndexingTestDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
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
 * Manual metadata correction end to end: rights at the document-editing threshold, the MANUAL
 * origin with actor, the audit event per document and field with old and new value, the chunk
 * rewrite, the Sammelzuweisung with rejected foreign ids - and the promise that a manual value
 * survives the deterministic Bestandslauf.
 */
@OpaaIndexingIntegrationTest
class DocumentMetadataCorrectionServiceIntegrationTest {

  private static final Path classTempDir =
      OpaaIndexingTestDirectory.subdirectory("metadata-correction");

  @Autowired private DocumentMetadataCorrectionService correctionService;
  @Autowired private DocumentMetadataService documentMetadataService;
  @Autowired private MetadataBackfillService backfillService;
  @Autowired private FileProcessingService fileProcessingService;
  @Autowired private DocumentMetadataValueRepository valueRepository;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private LibraryAccessService accessService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private KnowledgeLibrary library;
  private KnowledgeLibrary otherLibrary;
  private CurrentUser owner;
  private CurrentUser editor;
  private CurrentUser viewer;
  private CurrentUser stranger;

  @BeforeEach
  void setUp() throws IOException {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store, chunk_full_text");
    jdbcTemplate.update("DELETE FROM documents");
    jdbcTemplate.update("DELETE FROM asset_grants");
    jdbcTemplate.update("DELETE FROM knowledge_libraries WHERE name LIKE 'Korrektur%'");
    jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'metadata-correction-%'");
    owner = user("owner", SystemRole.USER);
    editor = user("editor", SystemRole.USER);
    viewer = user("viewer", SystemRole.USER);
    stranger = user("stranger", SystemRole.USER);
    library = library("Korrektur", classTempDir);
    otherLibrary = library("Korrektur-andere", classTempDir.resolve("other"));
    grant(library, editor, AssetRole.EDITOR);
    grant(library, viewer, AssetRole.VIEWER);
    Files.createDirectories(classTempDir.resolve("other"));
    try (var files = Files.list(classTempDir)) {
      for (Path file : files.toList()) {
        if (Files.isRegularFile(file)) {
          Files.deleteIfExists(file);
        }
      }
    }
  }

  @Test
  void anEditorSetsChangesAndDeletesAValueWithManualOriginAndActor() throws IOException {
    Document document = indexed("2026-03-12_Dienstanweisung_IT-Nutzung.pdf");
    CoreMetadata extracted = documentMetadataService.coreMetadataFor(document.getId());
    assertThat(extracted.documentTypeOrigin()).isEqualTo(MetadataOrigin.DETERMINISTIC);

    DocumentMetadataFieldView set =
        correctionService.setValue(
            library.getId(),
            document.getId(),
            "document_type",
            MetadataValueInput.vocabulary("VERMERK"),
            editor);
    assertThat(set.value().origin()).isEqualTo(MetadataOrigin.MANUAL);
    assertThat(set.value().actorUserId()).isEqualTo(editor.id());
    assertThat(set.value().extractionVersion()).isNull();
    assertThat(set.value().confidence()).isNull();
    assertThat(set.displayValue()).isEqualTo("Vermerk");
    assertThat(set.actorDisplayName()).isEqualTo(editor.displayName());

    DocumentMetadataFieldView changed =
        correctionService.setValue(
            library.getId(),
            document.getId(),
            "document_type",
            MetadataValueInput.vocabulary("PROTOKOLL"),
            editor);
    assertThat(changed.value().vocabularyCode()).isEqualTo("PROTOKOLL");
    assertThat(valueRepository.findByDocumentIdAndFieldKey(document.getId(), "document_type"))
        .get()
        .satisfies(
            row -> {
              assertThat(row.getOrigin()).isEqualTo(MetadataOrigin.MANUAL);
              assertThat(row.getActorUserId()).isEqualTo(editor.id());
            });

    correctionService.deleteValue(library.getId(), document.getId(), "document_type", editor);
    assertThat(valueRepository.findByDocumentIdAndFieldKey(document.getId(), "document_type"))
        .isEmpty();
    List<DocumentMetadataFieldView> fields =
        correctionService.fieldsOf(library.getId(), document.getId(), viewer);
    assertThat(fields)
        .extracting(DocumentMetadataFieldView::fieldKey)
        .containsExactly("title", "document_type", "document_date");
    assertThat(fields.get(1).value()).isNull();
    assertThat(fields.get(0).value().origin()).isEqualTo(MetadataOrigin.DETERMINISTIC);
  }

  @Test
  void aValueOutsideTheVocabularyOrOfTheWrongKindIsRejected() throws IOException {
    Document document = indexed("2026-03-12_Dienstanweisung_IT-Nutzung.pdf");

    assertThatThrownBy(
            () ->
                correctionService.setValue(
                    library.getId(),
                    document.getId(),
                    "document_type",
                    MetadataValueInput.vocabulary("RUNDSCHREIBEN"),
                    editor))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Unbekannte Dokumentart");
    assertThatThrownBy(
            () ->
                correctionService.setValue(
                    library.getId(),
                    document.getId(),
                    "title",
                    MetadataValueInput.text("  "),
                    editor))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                correctionService.setValue(
                    library.getId(),
                    document.getId(),
                    "document_date",
                    MetadataValueInput.text("2024"),
                    editor))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                correctionService.setValue(
                    library.getId(),
                    document.getId(),
                    "author",
                    MetadataValueInput.text("x"),
                    editor))
        .isInstanceOf(ValidationException.class);
    assertThat(auditEvents()).isEmpty();
  }

  @Test
  void aViewerMayReadButNotCorrectAndAStrangerSeesNothing() throws IOException {
    Document document = indexed("2026-03-12_Dienstanweisung_IT-Nutzung.pdf");

    assertThat(correctionService.fieldsOf(library.getId(), document.getId(), viewer)).hasSize(3);
    assertThatThrownBy(
            () ->
                correctionService.setValue(
                    library.getId(),
                    document.getId(),
                    "title",
                    MetadataValueInput.text("Neu"),
                    viewer))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () -> correctionService.deleteValue(library.getId(), document.getId(), "title", viewer))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () -> correctionService.fieldsOf(library.getId(), document.getId(), stranger))
        .isInstanceOf(NotFoundException.class);
    CurrentUser foreign = CurrentUser.of(editor.id(), UUID.randomUUID(), SystemRole.USER, "Fremd");
    assertThatThrownBy(() -> correctionService.fieldsOf(library.getId(), document.getId(), foreign))
        .isInstanceOf(NotFoundException.class);
    // A document of another library is as absent as one that does not exist.
    Document elsewhere = indexedIn(otherLibrary, "Protokoll_Sitzung_2025-11.pdf");
    assertThatThrownBy(
            () ->
                correctionService.setValue(
                    library.getId(),
                    elsewhere.getId(),
                    "title",
                    MetadataValueInput.text("Neu"),
                    editor))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void aManualValueSurvivesTheDeterministicBestandslauf() throws IOException {
    Document document = indexed("2026-03-12_Dienstanweisung_IT-Nutzung.pdf");
    correctionService.setValue(
        library.getId(),
        document.getId(),
        "document_type",
        MetadataValueInput.vocabulary("VERMERK"),
        editor);
    correctionService.setValue(
        library.getId(),
        document.getId(),
        "document_date",
        MetadataValueInput.date(LocalDate.of(2024, 5, 17), DatePrecision.YEAR),
        editor);
    // The Altbestand shape: the run has to re-extract this document from its file.
    jdbcTemplate.update("UPDATE documents SET metadata_extraction_version = NULL");

    backfillService.backfillBatch(Organization.DEFAULT_ID, library.getId(), 10);

    CoreMetadata core = documentMetadataService.coreMetadataFor(document.getId());
    assertThat(core.documentTypeCode()).isEqualTo("VERMERK");
    assertThat(core.documentTypeOrigin()).isEqualTo(MetadataOrigin.MANUAL);
    assertThat(core.documentDate()).isEqualTo(LocalDate.of(2024, 1, 1));
    assertThat(core.documentDatePrecision()).isEqualTo(DatePrecision.YEAR);
    assertThat(core.documentDateOrigin()).isEqualTo(MetadataOrigin.MANUAL);
    // The title was not touched by hand and is re-extracted as usual.
    assertThat(core.titleOrigin()).isEqualTo(MetadataOrigin.DETERMINISTIC);
    assertThat(
            documentRepository
                .findById(document.getId())
                .orElseThrow()
                .getMetadataExtractionVersion())
        .isEqualTo(CoreMetadataExtractor.EXTRACTION_VERSION);
  }

  /**
   * The promise behind deleting a value: the field is empty, not locked - the next automatic
   * extraction may fill it again. The Bestandslauf only selects documents whose extraction version
   * is missing or outdated, so deleting has to reset that version or the promise is empty.
   */
  @Test
  void aDeletedValueIsRefilledByTheNextBestandslauf() throws IOException {
    Document document = indexed("2026-03-12_Dienstanweisung_IT-Nutzung.pdf");
    assertThat(documentMetadataService.coreMetadataFor(document.getId()).documentTypeCode())
        .isEqualTo("DIENSTANWEISUNG");

    correctionService.deleteValue(library.getId(), document.getId(), "document_type", editor);
    assertThat(valueRepository.findByDocumentIdAndFieldKey(document.getId(), "document_type"))
        .isEmpty();
    assertThat(
            documentRepository
                .findById(document.getId())
                .orElseThrow()
                .getMetadataExtractionVersion())
        .as("a deleted value hands the document back to the Bestandslauf")
        .isNull();

    MetadataBackfillResult run =
        backfillService.backfillBatch(Organization.DEFAULT_ID, library.getId(), 10);

    assertThat(run.processedDocuments()).isEqualTo(1);
    CoreMetadata core = documentMetadataService.coreMetadataFor(document.getId());
    assertThat(core.documentTypeCode()).isEqualTo("DIENSTANWEISUNG");
    assertThat(core.documentTypeOrigin()).isEqualTo(MetadataOrigin.DETERMINISTIC);
    assertThat(chunkMetadata(document.getId()))
        .allSatisfy(metadata -> assertThat(metadata).containsEntry("doc_type", "DIENSTANWEISUNG"));
  }

  @Test
  void everySetAndDeleteRewritesTheFilterableChunkKeysInPlace() throws IOException {
    Document document = indexed("2026-03-12_Dienstanweisung_IT-Nutzung.pdf");
    List<UUID> chunkIdsBefore = chunkIds(document.getId());
    assertThat(chunkIdsBefore).isNotEmpty();

    correctionService.setValue(
        library.getId(),
        document.getId(),
        "document_type",
        MetadataValueInput.vocabulary("VERMERK"),
        editor);
    assertThat(chunkMetadata(document.getId()))
        .allSatisfy(metadata -> assertThat(metadata).containsEntry("doc_type", "VERMERK"));

    correctionService.setValue(
        library.getId(),
        document.getId(),
        "document_date",
        MetadataValueInput.date(LocalDate.of(2025, 11, 3), DatePrecision.MONTH),
        editor);
    assertThat(chunkMetadata(document.getId()))
        .allSatisfy(
            metadata -> {
              assertThat(metadata).containsEntry("doc_date", "2025-11-01");
              assertThat(metadata).containsEntry("doc_date_precision", "MONTH");
            });

    correctionService.deleteValue(library.getId(), document.getId(), "document_type", editor);
    assertThat(chunkMetadata(document.getId()))
        .allSatisfy(
            metadata -> {
              assertThat(metadata).doesNotContainKey("doc_type");
              assertThat(metadata).containsEntry("doc_date", "2025-11-01");
            });
    assertThat(chunkIds(document.getId())).containsExactlyElementsOf(chunkIdsBefore);
  }

  @Test
  void aBulkAssignmentSetsEveryOwnDocumentAndRejectsForeignIdsWithoutSkippingThem()
      throws IOException {
    Document first = indexed("2026-03-12_Dienstanweisung_IT-Nutzung.pdf");
    Document second = indexed("Protokoll_Sitzung_2025-11.pdf");
    Document third = indexed("Formular_Antrag.pdf");
    Document elsewhere = indexedIn(otherLibrary, "Vermerk_Haushalt.pdf");
    UUID unknown = UUID.randomUUID();
    // Already carries the target value by hand: counted as unchanged, no second event.
    correctionService.setValue(
        library.getId(),
        second.getId(),
        "document_type",
        MetadataValueInput.vocabulary("SATZUNG_ORDNUNG"),
        editor);
    int eventsBefore = auditEvents().size();

    BulkMetadataResult result =
        correctionService.bulkSetValue(
            library.getId(),
            "document_type",
            MetadataValueInput.vocabulary("SATZUNG_ORDNUNG"),
            List.of(
                first.getId(),
                second.getId(),
                third.getId(),
                elsewhere.getId(),
                unknown,
                first.getId()),
            editor);

    assertThat(result.updatedCount()).isEqualTo(2);
    assertThat(result.unchangedCount()).isEqualTo(1);
    assertThat(result.rejectedDocumentIds()).containsExactly(elsewhere.getId(), unknown);
    assertThat(result.correlationRef()).startsWith("metadata-bulk-");
    for (Document own : List.of(first, second, third)) {
      CoreMetadata core = documentMetadataService.coreMetadataFor(own.getId());
      assertThat(core.documentTypeCode()).isEqualTo("SATZUNG_ORDNUNG");
      assertThat(core.documentTypeOrigin()).isEqualTo(MetadataOrigin.MANUAL);
    }
    assertThat(documentMetadataService.coreMetadataFor(elsewhere.getId()).documentTypeCode())
        .as("a foreign document is never touched")
        .isNotEqualTo("SATZUNG_ORDNUNG");
    List<AuditRow> bulkEvents =
        auditEvents().stream()
            .filter(entry -> result.correlationRef().equals(entry.getCorrelationRef()))
            .toList();
    // One event per changed document, none for the unchanged one, all under one correlationRef.
    assertThat(auditEvents()).hasSize(eventsBefore + 2);
    assertThat(bulkEvents).hasSize(2);
    assertThat(bulkEvents)
        .extracting(entry -> parse(entry.getAfter()).get("documentId"))
        .containsExactlyInAnyOrder(first.getId().toString(), third.getId().toString());
    assertThat(bulkEvents)
        .allSatisfy(
            entry -> {
              assertThat(parse(entry.getAfter()))
                  .containsEntry("value", "SATZUNG_ORDNUNG")
                  .containsEntry("origin", "MANUAL");
              assertThat(parse(entry.getBefore())).containsEntry("origin", "DETERMINISTIC");
            });
    assertThatThrownBy(
            () ->
                correctionService.bulkSetValue(
                    library.getId(),
                    "document_type",
                    MetadataValueInput.vocabulary("SATZUNG_ORDNUNG"),
                    List.of(first.getId()),
                    viewer))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void theEventSequenceCarriesOldAndNewValueAndRebuildsTheManualState() throws IOException {
    Document document = indexed("2026-03-12_Dienstanweisung_IT-Nutzung.pdf");
    correctionService.setValue(
        library.getId(),
        document.getId(),
        "document_type",
        MetadataValueInput.vocabulary("VERMERK"),
        editor);
    correctionService.setValue(
        library.getId(),
        document.getId(),
        "document_type",
        MetadataValueInput.vocabulary("PROTOKOLL"),
        editor);
    correctionService.setValue(
        library.getId(), document.getId(), "title", MetadataValueInput.text("Neuer Titel"), editor);
    correctionService.deleteValue(library.getId(), document.getId(), "title", editor);
    correctionService.setValue(
        library.getId(),
        document.getId(),
        "document_date",
        MetadataValueInput.date(LocalDate.of(2024, 3, 1), DatePrecision.MONTH),
        editor);
    // Repeating the identical value is not a change and writes no event.
    correctionService.setValue(
        library.getId(),
        document.getId(),
        "document_date",
        MetadataValueInput.date(LocalDate.of(2024, 3, 1), DatePrecision.MONTH),
        editor);

    List<AuditRow> events = auditEvents();
    assertThat(events).hasSize(5);
    assertThat(events)
        .allSatisfy(
            entry -> {
              assertThat(entry.getEventType()).isEqualTo("DOCUMENT_METADATA_CHANGED");
              assertThat(entry.getObjectType()).isEqualTo("KNOWLEDGE_LIBRARY");
              assertThat(entry.getObjectId()).isEqualTo(library.getId().toString());
              assertThat(entry.getActorRef()).isNotEqualTo(editor.id().toString());
              assertThat(entry.getCorrelationRef()).isNull();
              assertThat(parse(entry.getBefore()))
                  .containsEntry("documentId", document.getId().toString());
            });
    Map<String, Object> firstBefore = parse(events.get(0).getBefore());
    assertThat(firstBefore)
        .containsEntry("fieldKey", "document_type")
        .containsEntry("value", "DIENSTANWEISUNG")
        .containsEntry("origin", "DETERMINISTIC")
        .containsEntry("extractionVersion", CoreMetadataExtractor.EXTRACTION_VERSION);
    assertThat(parse(events.get(1).getBefore()))
        .containsEntry("value", "VERMERK")
        .containsEntry("origin", "MANUAL");
    assertThat(parse(events.get(1).getAfter()))
        .containsEntry("value", "PROTOKOLL")
        .containsEntry("displayValue", "Protokoll");
    assertThat(parse(events.get(3).getAfter())).containsEntry("state", "EMPTY");
    assertThat(parse(events.get(4).getAfter()))
        .containsEntry("value", "2024-03-01")
        .containsEntry("datePrecision", "MONTH")
        .containsEntry("displayValue", "03/2024");

    // Replaying the events in order yields exactly the manual state the rows hold now.
    Map<String, Map<String, Object>> replayed = new HashMap<>();
    for (AuditRow entry : events) {
      Map<String, Object> after = parse(entry.getAfter());
      String key = after.get("documentId") + "/" + after.get("fieldKey");
      if ("EMPTY".equals(after.get("state"))) {
        replayed.remove(key);
      } else {
        replayed.put(key, after);
      }
    }
    Map<String, String> stored = new HashMap<>();
    for (DocumentMetadataValue row : valueRepository.findByDocumentId(document.getId())) {
      if (row.getOrigin() == MetadataOrigin.MANUAL) {
        stored.put(
            row.getDocumentId() + "/" + row.getFieldKey(), MetadataValueSnapshot.of(row).value());
      }
    }
    assertThat(replayed.keySet()).containsExactlyInAnyOrderElementsOf(stored.keySet());
    replayed.forEach((key, after) -> assertThat(after).containsEntry("value", stored.get(key)));
  }

  private CurrentUser user(String name, SystemRole role) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, ?, now(), ?, ?)",
        id,
        "metadata-correction-" + id,
        "metadata-correction-" + name + "-" + id + "@example.com",
        "Korrektur " + name,
        role.name(),
        Organization.DEFAULT_ID);
    return CurrentUser.of(id, Organization.DEFAULT_ID, role, "Korrektur " + name);
  }

  private KnowledgeLibrary library(String name, Path sourcePath) {
    return libraryRepository.save(
        KnowledgeLibrary.ownedByUser(
            Organization.DEFAULT_ID,
            name,
            null,
            owner.id(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.FILESYSTEM,
            sourcePath.toString(),
            null,
            null,
            null,
            false));
  }

  private void grant(KnowledgeLibrary target, CurrentUser subject, AssetRole role) {
    grantRepository.save(
        AssetGrant.forUser(
            target.getId(), Organization.DEFAULT_ID, subject.id(), role, null, owner.id()));
    accessService.invalidateLibrary(target.getId());
  }

  private Document indexed(String fileName) throws IOException {
    return indexedIn(library, fileName);
  }

  private Document indexedIn(KnowledgeLibrary target, String fileName) throws IOException {
    Path file = Path.of(target.getSourcePath()).resolve(fileName);
    writePdf(file, fileName.replace(".pdf", "").replace('_', ' '));
    assertThat(fileProcessingService.processFile(file, target))
        .isEqualTo(FileProcessingResult.PROCESSED);
    return documentRepository.findAll().stream()
        .filter(document -> fileName.equals(document.getFileName()))
        .filter(document -> target.getId().equals(document.getLibraryId()))
        .findFirst()
        .orElseThrow();
  }

  /** One {@code audit_log} row of this library's metadata events, in recording order. */
  private record AuditRow(
      String eventType,
      String objectType,
      String objectId,
      String actorRef,
      String correlationRef,
      String before,
      String after) {
    String getEventType() {
      return eventType;
    }

    String getObjectType() {
      return objectType;
    }

    String getObjectId() {
      return objectId;
    }

    String getActorRef() {
      return actorRef;
    }

    String getCorrelationRef() {
      return correlationRef;
    }

    String getBefore() {
      return before;
    }

    String getAfter() {
      return after;
    }
  }

  private List<AuditRow> auditEvents() {
    return jdbcTemplate.query(
        "SELECT event_type, object_type, object_id, actor_ref, correlation_ref, before, after"
            + " FROM audit_log WHERE event_type = 'DOCUMENT_METADATA_CHANGED' AND object_id = ?"
            + " ORDER BY recorded_at, event_id",
        (rs, i) ->
            new AuditRow(
                rs.getString("event_type"),
                rs.getString("object_type"),
                rs.getString("object_id"),
                rs.getString("actor_ref"),
                rs.getString("correlation_ref"),
                rs.getString("before"),
                rs.getString("after")),
        library.getId().toString());
  }

  private List<UUID> chunkIds(UUID documentId) {
    return jdbcTemplate.query(
        "SELECT id FROM vector_store WHERE metadata->>'document_id' = ? ORDER BY id",
        (rs, i) -> UUID.fromString(rs.getString("id")),
        documentId.toString());
  }

  private List<Map<String, Object>> chunkMetadata(UUID documentId) {
    return jdbcTemplate.query(
        "SELECT metadata::text AS metadata FROM vector_store WHERE metadata->>'document_id' = ?",
        (rs, i) -> parse(rs.getString("metadata")),
        documentId.toString());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parse(String json) {
    return new tools.jackson.databind.ObjectMapper().readValue(json, Map.class);
  }

  private static void writePdf(Path file, String title) throws IOException {
    Files.createDirectories(file.getParent());
    try (PDDocument doc = new PDDocument()) {
      for (String text :
          List.of(
              "Diese Anweisung regelt die Nutzung der IT.",
              "Passwoerter sind vertraulich zu behandeln.")) {
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
      doc.getDocumentInformation().setTitle(title);
      doc.save(file.toFile());
    }
  }
}
