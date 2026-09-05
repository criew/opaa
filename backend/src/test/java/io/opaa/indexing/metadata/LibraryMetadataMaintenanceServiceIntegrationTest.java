package io.opaa.indexing.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DatePrecision;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.MetadataOrigin;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentIngest;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.library.AssetGrant;
import io.opaa.library.AssetGrantRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.library.LibraryAccessService;
import io.opaa.library.LibraryDocumentPage;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The Pflege-Anker and the third state "kein Wert ermittelbar" end to end: what the anchor counts,
 * that it is built in the rights context of the asking person, that the anchor's list holds exactly
 * the counted documents, and that no automatic extraction ever writes or clears the third state -
 * while filters and the Beleg see it as empty.
 */
@OpaaIndexingIntegrationTest
class LibraryMetadataMaintenanceServiceIntegrationTest {

  private static final Path classTempDir =
      OpaaIndexingTestDirectory.subdirectory("metadata-maintenance");

  @Autowired private LibraryMetadataMaintenanceService maintenanceService;
  @Autowired private DocumentMetadataCorrectionService correctionService;
  @Autowired private DocumentMetadataService documentMetadataService;
  @Autowired private MetadataBackfillService backfillService;
  @Autowired private FileProcessingService fileProcessingService;
  @Autowired private KnowledgeLibraryService libraryService;
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
  private CurrentUser bothLibraries;
  private CurrentUser oneLibrary;

  @BeforeEach
  void setUp() throws IOException {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store, chunk_full_text");
    jdbcTemplate.update("DELETE FROM documents");
    jdbcTemplate.update("DELETE FROM asset_grants");
    jdbcTemplate.update("DELETE FROM knowledge_libraries WHERE name LIKE 'Anker%'");
    jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'metadata-anchor-%'");
    owner = user("owner");
    editor = user("editor");
    bothLibraries = user("beide");
    oneLibrary = user("eine");
    library = library("Anker", classTempDir);
    otherLibrary = library("Anker-andere", classTempDir.resolve("other"));
    grant(library, editor, AssetRole.EDITOR);
    grant(library, bothLibraries, AssetRole.VIEWER);
    grant(otherLibrary, bothLibraries, AssetRole.VIEWER);
    grant(library, oneLibrary, AssetRole.VIEWER);
    Files.createDirectories(classTempDir.resolve("other"));
    deletePdfsIn(classTempDir);
    deletePdfsIn(classTempDir.resolve("other"));
  }

  @Test
  void theAnchorCountsEmptyFieldsButNotTheOnesMarkedAsNotDeterminable() throws IOException {
    // Three documents; the file names carry a Dokumentart for exactly one of them.
    Document withType = indexed("2026-03-12_Dienstanweisung_IT-Nutzung.pdf");
    Document undeterminable = indexed("Notiz_ohne_Art.pdf");
    indexed("Zweite_Notiz.pdf");
    assertThat(documentMetadataService.coreMetadataFor(withType.getId()).documentTypeCode())
        .isEqualTo("DIENSTANWEISUNG");

    assertThat(anchorOf("document_type", bothLibraries).documentsWithoutValue())
        .as("two documents have no Dokumentart yet")
        .isEqualTo(2);

    correctionService.setValue(
        library.getId(),
        undeterminable.getId(),
        "document_type",
        MetadataValueInput.notDeterminable(),
        editor);

    MetadataFieldMaintenance anchor = anchorOf("document_type", bothLibraries);
    assertThat(anchor.documentsWithoutValue())
        .as("a field marked 'kein Wert ermittelbar' is done, not open")
        .isEqualTo(1);
    assertThat(anchor.notDeterminableDocuments()).isEqualTo(1);
    assertThat(anchor.filledDocuments()).isEqualTo(1);
    assertThat(anchor.totalDocuments()).isEqualTo(3);
    assertThat(anchor.missingShare()).isEqualTo(1d / 3);

    // The rest is workable to zero - the point of the third state.
    correctionService.bulkSetValue(
        library.getId(),
        "document_type",
        MetadataValueInput.notDeterminable(),
        List.of(withType.getId(), undeterminable.getId()),
        editor);
    correctionService.setValue(
        library.getId(),
        lastDocument("Zweite_Notiz.pdf").getId(),
        "document_type",
        MetadataValueInput.vocabulary("VERMERK"),
        editor);
    assertThat(anchorOf("document_type", bothLibraries).documentsWithoutValue()).isZero();
  }

  /**
   * The Rechte-Invariante: the anchor is an aggregate over documents and only ever exists in the
   * rights context of the person asking - the same library answers a number to one person and
   * "absent" to another.
   */
  @Test
  void twoPeopleWithDifferentSightGetDifferentAnswers() throws IOException {
    indexed("2026-03-12_Dienstanweisung_IT-Nutzung.pdf");
    indexed("Notiz_ohne_Art.pdf");
    indexedIn(otherLibrary, "Vermerk_Haushalt.pdf");

    assertThat(maintenanceOf(library.getId(), oneLibrary).totalDocuments()).isEqualTo(2);
    assertThat(maintenanceOf(library.getId(), bothLibraries).totalDocuments()).isEqualTo(2);
    assertThat(maintenanceOf(otherLibrary.getId(), bothLibraries).totalDocuments()).isEqualTo(1);
    assertThatThrownBy(() -> maintenanceOf(otherLibrary.getId(), oneLibrary))
        .as("a library this person may not read is absent, not a number")
        .isInstanceOf(NotFoundException.class);
    CurrentUser foreignOrganization =
        CurrentUser.of(oneLibrary.id(), UUID.randomUUID(), SystemRole.USER, "Fremd");
    assertThatThrownBy(() -> maintenanceOf(library.getId(), foreignOrganization))
        .isInstanceOf(NotFoundException.class);
  }

  /** The anchor's list holds exactly the documents the anchor counts, and can be emptied. */
  @Test
  void theFilteredListHoldsExactlyTheCountedDocuments() throws IOException {
    indexed("2026-03-12_Dienstanweisung_IT-Nutzung.pdf");
    Document firstOpen = indexed("Notiz_ohne_Art.pdf");
    Document secondOpen = indexed("Zweite_Notiz.pdf");

    LibraryDocumentPage page = listWithoutValue("document_type", bothLibraries);
    assertThat(page.totalElements())
        .isEqualTo(anchorOf("document_type", bothLibraries).documentsWithoutValue());
    assertThat(page.documents())
        .extracting(entry -> entry.document().getFileName())
        .containsExactlyInAnyOrder("Notiz_ohne_Art.pdf", "Zweite_Notiz.pdf");

    correctionService.setValue(
        library.getId(),
        firstOpen.getId(),
        "document_type",
        MetadataValueInput.notDeterminable(),
        editor);
    assertThat(listWithoutValue("document_type", bothLibraries).documents())
        .as("a document marked 'kein Wert ermittelbar' leaves the list")
        .extracting(entry -> entry.document().getFileName())
        .containsExactly("Zweite_Notiz.pdf");

    correctionService.setValue(
        library.getId(),
        secondOpen.getId(),
        "document_type",
        MetadataValueInput.vocabulary("VERMERK"),
        editor);
    assertThat(listWithoutValue("document_type", bothLibraries).documents()).isEmpty();
  }

  @Test
  void theThirdStateIsWrittenByHandWithAnAuditEventCarryingTheOldValue() throws IOException {
    Document document = indexed("2026-03-12_Dienstanweisung_IT-Nutzung.pdf");

    DocumentMetadataFieldView view =
        correctionService.setValue(
            library.getId(),
            document.getId(),
            "document_type",
            MetadataValueInput.notDeterminable(),
            editor);

    assertThat(view.value().state()).isEqualTo(MetadataValueState.NOT_DETERMINABLE);
    assertThat(view.value().origin()).isEqualTo(MetadataOrigin.MANUAL);
    assertThat(view.value().actorUserId()).isEqualTo(editor.id());
    assertThat(view.value().value()).isNull();
    List<Map<String, Object>> events = auditEvents();
    assertThat(events).hasSize(1);
    assertThat(events.get(0))
        .as("the old value is in the event, so manual state stays rebuildable")
        .containsEntry("beforeValue", "DIENSTANWEISUNG")
        .containsEntry("afterState", "NOT_DETERMINABLE")
        .containsEntry("afterOrigin", "MANUAL");
    assertThat(events.get(0).get("afterValue")).isNull();

    // Setting it a second time changes nothing and writes no further event.
    correctionService.setValue(
        library.getId(),
        document.getId(),
        "document_type",
        MetadataValueInput.notDeterminable(),
        editor);
    assertThat(auditEvents()).hasSize(1);
  }

  /**
   * "Keine automatische Extraktion vergibt ihn, und keine setzt ihn zurück" - both directions, over
   * the two paths that write values automatically: the extraction on ingest and the Bestandslauf.
   */
  @Test
  void noAutomaticExtractionWritesOrClearsTheThirdState() throws IOException {
    Document document = indexed("2026-03-12_Dienstanweisung_IT-Nutzung.pdf");
    correctionService.setValue(
        library.getId(),
        document.getId(),
        "document_type",
        MetadataValueInput.notDeterminable(),
        editor);
    correctionService.setValue(
        library.getId(),
        document.getId(),
        "document_date",
        MetadataValueInput.notDeterminable(),
        editor);

    documentMetadataService.reextractFromFile(
        documentRepository.findById(document.getId()).orElseThrow(),
        Path.of(library.getSourcePath()).resolve(document.getFileName()));
    jdbcTemplate.update("UPDATE documents SET metadata_extraction_version = NULL");
    backfillService.backfillBatch(Organization.DEFAULT_ID, library.getId(), 10);

    assertThat(valueRepository.findByDocumentIdAndFieldKey(document.getId(), "document_type"))
        .get()
        .satisfies(
            row -> {
              assertThat(row.getState()).isEqualTo(MetadataValueState.NOT_DETERMINABLE);
              assertThat(row.getOrigin()).isEqualTo(MetadataOrigin.MANUAL);
              assertThat(row.getVocabularyCode()).isNull();
            });
    assertThat(anchorOf("document_type", bothLibraries).documentsWithoutValue())
        .as("the Bestandslauf does not push the document back into the anchor")
        .isZero();
    // No extraction ever creates the state on its own - the file's own Dokumentart is
    // deterministic.
    assertThat(valueRepository.findByDocumentId(indexed("Protokoll_Sitzung_2025-11.pdf").getId()))
        .allSatisfy(row -> assertThat(row.getState()).isEqualTo(MetadataValueState.SET));
  }

  /**
   * For filters and the Beleg the third state behaves like an empty field: the effective core
   * metadata carries nothing, so the filterable chunk keys are gone and the Beleg's metadata list
   * has no entry for the field.
   */
  @Test
  void theThirdStateBehavesLikeAnEmptyFieldForFilterAndBeleg() throws IOException {
    Document document = indexed("2026-03-12_Dienstanweisung_IT-Nutzung.pdf");
    correctionService.setValue(
        library.getId(),
        document.getId(),
        "document_date",
        MetadataValueInput.date(LocalDate.of(2026, 3, 12), DatePrecision.DAY),
        editor);
    assertThat(chunkMetadata(document.getId()))
        .isNotEmpty()
        .allSatisfy(
            metadata -> assertThat(metadata).containsKey("doc_type").containsKey("doc_date"));

    correctionService.setValue(
        library.getId(),
        document.getId(),
        "document_type",
        MetadataValueInput.notDeterminable(),
        editor);
    correctionService.setValue(
        library.getId(),
        document.getId(),
        "document_date",
        MetadataValueInput.notDeterminable(),
        editor);

    assertThat(chunkMetadata(document.getId()))
        .allSatisfy(
            metadata ->
                assertThat(metadata)
                    .doesNotContainKey("doc_type")
                    .doesNotContainKey("doc_date")
                    .doesNotContainKey("doc_date_precision"));
    CoreMetadata core = documentMetadataService.coreMetadataFor(document.getId());
    assertThat(core.documentTypeCode()).isNull();
    assertThat(core.documentDate()).isNull();
    assertThat(io.opaa.chat.ChatSourceMetadataEntry.fromCore(core))
        .extracting(io.opaa.chat.ChatSourceMetadataEntry::fieldKey)
        .containsExactly("title");
  }

  private MetadataFieldMaintenance anchorOf(String fieldKey, CurrentUser caller) {
    return maintenanceOf(library.getId(), caller).fields().stream()
        .filter(field -> field.fieldKey().equals(fieldKey))
        .findFirst()
        .orElseThrow();
  }

  private LibraryMetadataMaintenance maintenanceOf(UUID libraryId, CurrentUser caller) {
    return maintenanceService.maintenanceOf(libraryId, caller);
  }

  private LibraryDocumentPage listWithoutValue(String fieldKey, CurrentUser caller) {
    return listWithoutValue(fieldKey, caller, null);
  }

  private LibraryDocumentPage listWithoutValue(String fieldKey, CurrentUser caller, String q) {
    return libraryService.listDocuments(
        library.getId(),
        caller,
        q,
        null,
        fieldKey,
        PageRequest.of(0, 20, Sort.by(Sort.Order.asc("fileName"), Sort.Order.asc("id"))));
  }

  private CurrentUser user(String name) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, ?, now(), ?, ?)",
        id,
        "metadata-anchor-" + id,
        "metadata-anchor-" + name + "-" + id + "@example.com",
        "Anker " + name,
        SystemRole.USER.name(),
        Organization.DEFAULT_ID);
    return CurrentUser.of(id, Organization.DEFAULT_ID, SystemRole.USER, "Anker " + name);
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

  /** An indexed attachment row of {@code parent} - no file, only the row the anchor counts. */
  private Document attachmentOf(Document parent, String fileName) {
    Document attachment =
        new Document(
            fileName,
            parent.getFilePath() + "!/" + fileName,
            "application/pdf",
            1L,
            parent.getSourceType());
    attachment.setLibraryId(library.getId());
    attachment.setOrganizationId(Organization.DEFAULT_ID);
    attachment.setParentDocumentId(parent.getId());
    attachment.setStatus(DocumentStatus.INDEXED);
    return documentRepository.save(attachment);
  }

  private Document lastDocument(String fileName) {
    return documentRepository.findAll().stream()
        .filter(document -> fileName.equals(document.getFileName()))
        .findFirst()
        .orElseThrow();
  }

  private Document indexedIn(KnowledgeLibrary target, String fileName) throws IOException {
    Path file = Path.of(target.getSourcePath()).resolve(fileName);
    writePdf(file, fileName.replace(".pdf", "").replace('_', ' '));
    assertThat(fileProcessingService.ingest(DocumentIngest.localFile(target, file).build(), null))
        .isEqualTo(FileProcessingResult.PROCESSED);
    return documentRepository.findAll().stream()
        .filter(document -> fileName.equals(document.getFileName()))
        .filter(document -> target.getId().equals(document.getLibraryId()))
        .findFirst()
        .orElseThrow();
  }

  /** The flattened before/after of this library's metadata audit events, in recording order. */
  private List<Map<String, Object>> auditEvents() {
    return jdbcTemplate.query(
        "SELECT before, after FROM audit_log WHERE event_type = 'DOCUMENT_METADATA_CHANGED'"
            + " AND object_id = ? ORDER BY recorded_at, event_id",
        (rs, i) -> {
          Map<String, Object> before = parse(rs.getString("before"));
          Map<String, Object> after = parse(rs.getString("after"));
          return Map.of(
              "beforeValue", String.valueOf(before.get("value")),
              "afterState", String.valueOf(after.get("state")),
              "afterOrigin", String.valueOf(after.get("origin")));
        },
        library.getId().toString());
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

  private static void deletePdfsIn(Path directory) throws IOException {
    Files.createDirectories(directory);
    try (var files = Files.list(directory)) {
      for (Path file : files.toList()) {
        if (Files.isRegularFile(file)) {
          Files.deleteIfExists(file);
        }
      }
    }
  }

  private static void writePdf(Path file, String title) throws IOException {
    Files.createDirectories(file.getParent());
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(50, 700);
        content.showText("Diese Unterlage regelt die Nutzung der IT.");
        content.endText();
      }
      doc.getDocumentInformation().setTitle(title);
      doc.save(file.toFile());
    }
  }

  /**
   * The anchor's promise in one assertion: its number and the length of its list are the same
   * figure, over a mixed bestand of parents and attachments with and without a value. Everything
   * the list shows is genuinely open, so selecting the whole page and assigning a value cannot
   * overwrite a maintained one.
   */
  @Test
  void theAnchorNumberEqualsTheLengthOfItsListForParentsAndAttachmentsAlike() throws IOException {
    Document mail = indexed("Protokoll_Sitzung_2025-11.pdf");
    Document attachmentWithValue = attachmentOf(mail, "anhang-mit-wert.pdf");
    attachmentOf(mail, "anhang-ohne-wert.pdf");
    indexed("Notiz_ohne_Art.pdf");
    correctionService.setValue(
        library.getId(),
        attachmentWithValue.getId(),
        "document_type",
        MetadataValueInput.vocabulary("VERMERK"),
        editor);

    // The mail itself carries a Dokumentart from its file name; open are the two rows below.
    MetadataFieldMaintenance anchor = anchorOf("document_type", bothLibraries);
    assertThat(anchor.documentsWithoutValue()).isEqualTo(2);

    LibraryDocumentPage page = listWithoutValue("document_type", bothLibraries);
    assertThat(page.totalElements())
        .as("the list holds exactly the documents the anchor counts")
        .isEqualTo(anchor.documentsWithoutValue());
    assertThat(page.documents())
        .extracting(entry -> entry.document().getFileName())
        .containsExactlyInAnyOrder("anhang-ohne-wert.pdf", "Notiz_ohne_Art.pdf");
  }

  @Test
  void theListCombinesWithASearchTermAndRejectsAnUnknownField() throws IOException {
    Document mail = indexed("Protokoll_Sitzung_2025-11.pdf");
    attachmentOf(mail, "anhang-ohne-wert.pdf");
    indexed("Notiz_ohne_Art.pdf");

    assertThat(listWithoutValue("document_type", bothLibraries, "anhang").documents())
        .extracting(entry -> entry.document().getFileName())
        .containsExactly("anhang-ohne-wert.pdf");
    assertThatThrownBy(() -> listWithoutValue("autor", bothLibraries))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Unbekanntes Metadatenfeld");
  }

  @Test
  void deletingAThirdStateMarkPutsTheDocumentBackIntoTheAnchor() throws IOException {
    Document document = indexed("Notiz_ohne_Art.pdf");
    correctionService.setValue(
        library.getId(),
        document.getId(),
        "document_type",
        MetadataValueInput.notDeterminable(),
        editor);
    assertThat(anchorOf("document_type", bothLibraries).documentsWithoutValue()).isZero();

    correctionService.deleteValue(library.getId(), document.getId(), "document_type", editor);

    assertThat(anchorOf("document_type", bothLibraries).documentsWithoutValue()).isEqualTo(1);
    assertThat(listWithoutValue("document_type", bothLibraries).documents())
        .extracting(entry -> entry.document().getFileName())
        .containsExactly("Notiz_ohne_Art.pdf");
  }
}
