package io.opaa.indexing.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.DatePrecision;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.MetadataOrigin;
import io.opaa.api.types.SystemRole;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.indexing.pipeline.DocumentPipelineRegistry;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIndexingIntegrationTest;
import io.opaa.test.OpaaIndexingTestDirectory;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The core fields end to end (#1066, ADR-0024): a PDF, a DOCX and a Markdown file with frontmatter
 * go through {@link FileProcessingService#processFile}; their values land at the document with
 * origin and extraction version, their filterable keys on every chunk, and a manual value survives
 * a re-extraction that rewrites the chunk metadata without touching the chunks. Since #1263 also
 * the three further Dokumentart sources against the seeded vocabulary of the database: the
 * Kompositum ending in a file name, the document head, and the file format.
 */
@OpaaIndexingIntegrationTest
class CoreMetadataIndexingIntegrationTest {

  private static final Path classTempDir =
      OpaaIndexingTestDirectory.subdirectory("core-metadata-indexing");

  @Autowired private FileProcessingService fileProcessingService;
  @Autowired private DocumentMetadataService documentMetadataService;
  @Autowired private DocumentMetadataValueRepository valueRepository;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private DocumentTypeVocabularyRepository vocabularyRepository;
  @Autowired private DocumentPipelineRegistry pipelineRegistry;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private KnowledgeLibrary targetLibrary;

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store, chunk_full_text");
    documentRepository.deleteAll();
    jdbcTemplate.update(
        "DELETE FROM knowledge_libraries WHERE owner_user_id IN (SELECT id FROM users WHERE"
            + " email = 'core-metadata-it@example.com')");
    jdbcTemplate.update("DELETE FROM users WHERE email = 'core-metadata-it@example.com'");
    UUID userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', 'core-metadata-it@example.com',"
            + " 'Core Metadata IT User', now(), ?, ?)",
        userId,
        "core-metadata-it-" + userId,
        SystemRole.SYSTEM_ADMIN.name(),
        Organization.DEFAULT_ID);
    targetLibrary =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Kernfelder",
                null,
                userId,
                LibraryVisibility.PRIVATE,
                false));
  }

  @Test
  void pdfPropertiesAndFileNameConventionFillAllThreeFieldsAtTheDocumentAndOnEveryChunk()
      throws IOException {
    Path file = classTempDir.resolve("2026-03-12_Dienstanweisung_IT-Nutzung.pdf");
    writePdf(file, "Dienstanweisung zur IT-Nutzung", LocalDate.of(2025, 6, 30));

    assertThat(fileProcessingService.processFile(file, targetLibrary))
        .isEqualTo(FileProcessingResult.PROCESSED);

    Document document = documentRepository.findAll().getFirst();
    assertThat(document.getMetadataExtractionVersion())
        .isEqualTo(CoreMetadataExtractor.EXTRACTION_VERSION);
    List<DocumentMetadataValue> values = valueRepository.findByDocumentId(document.getId());
    assertThat(values).hasSize(3);
    assertThat(values)
        .allSatisfy(
            value -> {
              assertThat(value.getOrigin()).isEqualTo(MetadataOrigin.DETERMINISTIC);
              assertThat(value.getExtractionVersion())
                  .isEqualTo(CoreMetadataExtractor.EXTRACTION_VERSION);
              assertThat(value.getConfidence()).isNull();
              assertThat(value.getActorUserId()).isNull();
              assertThat(value.getCreatedAt()).isNotNull();
            });
    CoreMetadata core = documentMetadataService.coreMetadataFor(document.getId());
    // The Info dictionary's title beats the file name; the file name's ISO date beats the PDF's
    // own dates; the Dokumentart comes from the file name token.
    assertThat(core.title()).isEqualTo("Dienstanweisung zur IT-Nutzung");
    assertThat(core.documentTypeCode()).isEqualTo("DIENSTANWEISUNG");
    assertThat(core.documentTypeLabel()).isEqualTo("Dienstanweisung");
    assertThat(core.documentDate()).isEqualTo(LocalDate.of(2026, 3, 12));
    assertThat(core.documentDatePrecision()).isEqualTo(DatePrecision.DAY);

    assertThat(chunkMetadata(document.getId()))
        .isNotEmpty()
        .allSatisfy(
            metadata -> {
              assertThat(metadata).containsEntry("doc_type", "DIENSTANWEISUNG");
              assertThat(metadata).containsEntry("doc_date", "2026-03-12");
              assertThat(metadata).containsEntry("doc_date_precision", "DAY");
              assertThat(metadata).doesNotContainKey("title");
            });
  }

  @Test
  void docxCorePropertiesSupplyTitleAndDateWhenTheFileNameDeclaresNothing() throws IOException {
    Path file = classTempDir.resolve("anlage.docx");
    writeDocx(file, "Vermerk zur Fristsetzung", LocalDate.of(2024, 11, 5));

    assertThat(fileProcessingService.processFile(file, targetLibrary))
        .isEqualTo(FileProcessingResult.PROCESSED);

    Document document = documentRepository.findAll().getFirst();
    CoreMetadata core = documentMetadataService.coreMetadataFor(document.getId());
    assertThat(core.title()).isEqualTo("Vermerk zur Fristsetzung");
    assertThat(core.documentDate()).isEqualTo(LocalDate.of(2024, 11, 5));
    assertThat(core.documentDatePrecision()).isEqualTo(DatePrecision.DAY);
    // Neither the file name nor a property declares a Dokumentart: the field stays empty - no
    // row, no chunk key - rather than falling to any default.
    assertThat(core.documentTypeCode()).isNull();
    assertThat(valueRepository.findByDocumentId(document.getId())).hasSize(2);
    assertThat(chunkMetadata(document.getId()))
        .isNotEmpty()
        .allSatisfy(metadata -> assertThat(metadata).doesNotContainKey("doc_type"));
  }

  @Test
  void markdownFrontmatterIsADeterministicSourceMatchedExactlyAgainstTheVocabulary()
      throws IOException {
    Path file = classTempDir.resolve("verwaltung-0002_sozialgebuehrenbefreiungssatzung.md");
    Files.writeString(
        file,
        """
        ---
        titel: "Sozialgebührenbefreiungssatzung"
        dokumentart: "satzung"
        fassung: 2024
        stand_datum: "2024-01-01"
        ---

        # Sozialgebührenbefreiungssatzung

        ## § 1 Geltungsbereich

        Diese Satzung regelt die Befreiung von Gebühren.
        """);

    assertThat(fileProcessingService.processFile(file, targetLibrary))
        .isEqualTo(FileProcessingResult.PROCESSED);

    Document document = documentRepository.findAll().getFirst();
    CoreMetadata core = documentMetadataService.coreMetadataFor(document.getId());
    assertThat(core.title()).isEqualTo("Sozialgebührenbefreiungssatzung");
    assertThat(core.documentTypeCode()).isEqualTo("SATZUNG_ORDNUNG");
    assertThat(core.documentTypeLabel()).isEqualTo("Satzung/Ordnung");
    assertThat(core.documentDate()).isEqualTo(LocalDate.of(2024, 1, 1));
    assertThat(core.documentDatePrecision()).isEqualTo(DatePrecision.DAY);
  }

  /**
   * #1263: the demo's Satzungen carry the Dokumentart as a Kompositum in the file name - the exact
   * token match of #1066 does not see it, the seeded ending of migration 020 does.
   */
  @Test
  void aKompositumInTheFileNameNamesTheDokumentart() throws IOException {
    Path file = classTempDir.resolve("01_verwaltungsgebuehrensatzung.pdf");
    writePdf(file, null, null);

    assertThat(fileProcessingService.processFile(file, targetLibrary))
        .isEqualTo(FileProcessingResult.PROCESSED);

    Document document = documentRepository.findAll().getFirst();
    CoreMetadata core = documentMetadataService.coreMetadataFor(document.getId());
    assertThat(core.documentTypeCode()).isEqualTo("SATZUNG_ORDNUNG");
    assertThat(core.documentTypeOrigin()).isEqualTo(MetadataOrigin.DETERMINISTIC);
  }

  /**
   * #1263: the demo's Dienstanweisungen are named after their subject, not their Dokumentart - the
   * document head is the source that carries it.
   */
  @Test
  void theDocumentHeadNamesTheDokumentartWhenTheFileNameDoesNot() throws IOException {
    Path file = classTempDir.resolve("01_identitaetszweifel-ausweisantrag.docx");
    writeDocxWithHead(
        file,
        "Dienstanweisung Nr. 1 - Identitätszweifel beim Ausweisantrag",
        "Diese Regelung gilt fuer alle Mitarbeitenden des Buergerbueros.");

    assertThat(fileProcessingService.processFile(file, targetLibrary))
        .isEqualTo(FileProcessingResult.PROCESSED);

    Document document = documentRepository.findAll().getFirst();
    CoreMetadata core = documentMetadataService.coreMetadataFor(document.getId());
    assertThat(core.documentTypeCode()).isEqualTo("DIENSTANWEISUNG");
    assertThat(core.documentTypeOrigin()).isEqualTo(MetadataOrigin.DETERMINISTIC);
    assertThat(chunkMetadata(document.getId()))
        .isNotEmpty()
        .allSatisfy(metadata -> assertThat(metadata).containsEntry("doc_type", "DIENSTANWEISUNG"));
  }

  /** #1263: a presentation is a Präsentation - the format is the last source, and a sure one. */
  @Test
  void aPresentationGetsItsDokumentartFromTheFormatAlone() throws IOException {
    Path file = classTempDir.resolve("21_onboarding-buergerbuero.pptx");
    writePptx(file, "Onboarding Buergerbuero", "Ablauf der ersten Woche im Buergerbuero.");

    assertThat(fileProcessingService.processFile(file, targetLibrary))
        .isEqualTo(FileProcessingResult.PROCESSED);

    Document document = documentRepository.findAll().getFirst();
    CoreMetadata core = documentMetadataService.coreMetadataFor(document.getId());
    assertThat(core.documentTypeCode()).isEqualTo("PRAESENTATION");
    assertThat(core.documentTypeLabel()).isEqualTo("Präsentation");
    assertThat(core.documentTypeOrigin()).isEqualTo(MetadataOrigin.DETERMINISTIC);
    // The backfill reads the same three sources from the file alone, without chunking.
    assertThat(documentMetadataService.reextractFromFile(document, file).documentTypeCode())
        .isEqualTo("PRAESENTATION");
  }

  /**
   * #1263: an RSS entry names other documents than itself - neither its body text (no Kopfbereich)
   * nor its headline (no file name) may become a Dokumentart, and its headline is no Stand either.
   */
  @Test
  void anRssEntryNeitherReadsItsBodyAsAKopfbereichNorItsHeadlineAsAFileName() {
    assertThat(
            fileProcessingService.processRssEntry(
                // Two traps in the lead: the Kompositum "Hundesteuersatzung" and "Vortrag", a
                // seeded synonym of PRAESENTATION; two more in the headline: the Kompositum again
                // and a bare year that would look like a Stand.
                "Der Rat hat in seiner Sitzung die neue Hundesteuersatzung beschlossen. Der"
                    + " Vortrag dazu findet am Montag statt.",
                "Rat beschliesst Hundesteuersatzung fuer 2024",
                "https://feed.example/rat-beschluss",
                "2026-03-12T10:00:00Z",
                targetLibrary))
        .isEqualTo(FileProcessingResult.PROCESSED);

    Document document = documentRepository.findAll().getFirst();
    CoreMetadata core = documentMetadataService.coreMetadataFor(document.getId());
    assertThat(core.documentTypeCode()).isNull();
    assertThat(core.title()).isEqualTo("Rat beschliesst Hundesteuersatzung fuer 2024");
    // The feed's publication instant, not the year in the headline.
    assertThat(core.documentDate()).isEqualTo(LocalDate.of(2026, 3, 12));
    assertThat(core.documentDatePrecision()).isEqualTo(DatePrecision.DAY);
  }

  @Test
  void aDocumentTypeOutsideTheVocabularyLeavesTheFieldEmpty() throws IOException {
    Path file = classTempDir.resolve("Rundschreiben_2024.md");
    Files.writeString(
        file,
        """
        ---
        dokumentart: "formularhinweis"
        ---

        # Rundschreiben

        Hinweise zum Ausfüllen.
        """);

    fileProcessingService.processFile(file, targetLibrary);

    Document document = documentRepository.findAll().getFirst();
    CoreMetadata core = documentMetadataService.coreMetadataFor(document.getId());
    assertThat(core.documentTypeCode()).isNull();
    assertThat(core.documentDate()).isEqualTo(LocalDate.of(2024, 1, 1));
    assertThat(core.documentDatePrecision()).isEqualTo(DatePrecision.YEAR);
  }

  @Test
  void aManualValueSurvivesReextractionWhichRewritesChunkMetadataWithoutTouchingTheChunks()
      throws IOException {
    Path file = classTempDir.resolve("2026-03-12_Dienstanweisung_Homeoffice.pdf");
    writePdf(file, null, LocalDate.of(2025, 6, 30));
    fileProcessingService.processFile(file, targetLibrary);
    Document document = documentRepository.findAll().getFirst();
    List<UUID> chunkIdsBefore = chunkIds(document.getId());

    // A person overrides the Dokumentart (the ingest read DIENSTANWEISUNG from the file name) and
    // the title (which came from the file name's humanization).
    DocumentMetadataValue manualType =
        valueRepository.findByDocumentId(document.getId()).stream()
            .filter(v -> v.getFieldKey().equals(CoreMetadataField.DOCUMENT_TYPE.key()))
            .findFirst()
            .orElseThrow();
    valueRepository.delete(manualType);
    valueRepository.save(
        DocumentMetadataValue.manual(document.getId(), CoreMetadataField.DOCUMENT_TYPE, null)
            .assignVocabularyCode("VERMERK"));
    valueRepository.flush();

    CoreMetadata core = documentMetadataService.reextractFromFile(document, file);

    assertThat(core.documentTypeCode()).isEqualTo("VERMERK");
    assertThat(core.documentTypeOrigin()).isEqualTo(MetadataOrigin.MANUAL);
    assertThat(core.title()).isEqualTo("Dienstanweisung Homeoffice");
    assertThat(core.titleOrigin()).isEqualTo(MetadataOrigin.DETERMINISTIC);
    assertThat(chunkIds(document.getId()))
        .as("re-extraction never rewrites or re-embeds a chunk")
        .containsExactlyElementsOf(chunkIdsBefore);
    assertThat(chunkMetadata(document.getId()))
        .allSatisfy(metadata -> assertThat(metadata).containsEntry("doc_type", "VERMERK"));
  }

  @Test
  void anEmptiedFieldDisappearsFromDocumentAndChunksOnReextraction() throws IOException {
    Path file = classTempDir.resolve("Protokoll_Sitzung.pdf");
    writePdf(file, null, null);
    fileProcessingService.processFile(file, targetLibrary);
    Document document = documentRepository.findAll().getFirst();
    assertThat(documentMetadataService.coreMetadataFor(document.getId()).documentTypeCode())
        .isEqualTo("PROTOKOLL");
    // Simulate a corrected file name whose tokens no longer name a Dokumentart.
    jdbcTemplate.update(
        "UPDATE documents SET file_name = 'Sitzung.pdf' WHERE id = ?", document.getId());
    Document renamed = documentRepository.findById(document.getId()).orElseThrow();

    CoreMetadata core = documentMetadataService.reextractFromFile(renamed, file);

    assertThat(core.documentTypeCode()).isNull();
    assertThat(valueRepository.findByDocumentId(document.getId()))
        .noneMatch(v -> v.getFieldKey().equals(CoreMetadataField.DOCUMENT_TYPE.key()));
    assertThat(chunkMetadata(document.getId()))
        .isNotEmpty()
        .allSatisfy(metadata -> assertThat(metadata).doesNotContainKey("doc_type"));
  }

  /**
   * Review B2: document values and the chunk-key propagation are one transaction - a failing chunk
   * update leaves the document's rows and its extraction version exactly as they were.
   */
  @Test
  void aFailingChunkUpdateLeavesTheDocumentValuesAndExtractionVersionUntouched()
      throws IOException {
    Path file = classTempDir.resolve("Protokoll_Sitzung.pdf");
    writePdf(file, null, null);
    fileProcessingService.processFile(file, targetLibrary);
    Document document = documentRepository.findAll().getFirst();
    jdbcTemplate.update(
        "UPDATE documents SET file_name = 'Vermerk_2020-01-01.pdf', metadata_extraction_version ="
            + " NULL WHERE id = ?",
        document.getId());
    Document renamed = documentRepository.findById(document.getId()).orElseThrow();
    DocumentMetadataService withFailingChunkUpdate =
        new DocumentMetadataService(
            valueRepository,
            vocabularyRepository,
            documentRepository,
            pipelineRegistry,
            new VectorChunkStore(null, null, null, null, null) {
              @Override
              public int updateDocumentMetadata(
                  UUID id, Map<String, Object> values, Set<String> keysToClear) {
                throw new IllegalStateException("simulated chunk update failure");
              }
            },
            transactionManager);

    assertThatThrownBy(() -> withFailingChunkUpdate.reextractFromFile(renamed, file))
        .isInstanceOf(IllegalStateException.class);

    CoreMetadata core = documentMetadataService.coreMetadataFor(document.getId());
    assertThat(core.documentTypeCode()).isEqualTo("PROTOKOLL");
    assertThat(core.documentDate()).isNull();
    assertThat(
            documentRepository
                .findById(document.getId())
                .orElseThrow()
                .getMetadataExtractionVersion())
        .isNull();
    assertThat(chunkMetadata(document.getId()))
        .allSatisfy(metadata -> assertThat(metadata).containsEntry("doc_type", "PROTOKOLL"));
  }

  /**
   * Review S2: a model-derived value fills exactly the gap the deterministic step leaves - an empty
   * deterministic result must not delete it, only a real result replaces it.
   */
  @Test
  void aDerivedValueSurvivesAnEmptyDeterministicResultButYieldsToARealOne() throws IOException {
    Path file = classTempDir.resolve("anlage.pdf");
    writePdf(file, null, null);
    fileProcessingService.processFile(file, targetLibrary);
    Document document = documentRepository.findAll().getFirst();
    valueRepository.save(
        DocumentMetadataValue.derived(
                document.getId(), CoreMetadataField.DOCUMENT_TYPE, "test-model", 0.8, 1)
            .assignVocabularyCode("VERMERK"));
    valueRepository.flush();

    CoreMetadata afterEmptyResult = documentMetadataService.reextractFromFile(document, file);
    assertThat(afterEmptyResult.documentTypeCode()).isEqualTo("VERMERK");
    assertThat(afterEmptyResult.documentTypeOrigin()).isEqualTo(MetadataOrigin.DERIVED);

    jdbcTemplate.update(
        "UPDATE documents SET file_name = 'Protokoll_anlage.pdf' WHERE id = ?", document.getId());
    Document renamed = documentRepository.findById(document.getId()).orElseThrow();
    CoreMetadata afterRealResult = documentMetadataService.reextractFromFile(renamed, file);
    assertThat(afterRealResult.documentTypeCode()).isEqualTo("PROTOKOLL");
    assertThat(afterRealResult.documentTypeOrigin()).isEqualTo(MetadataOrigin.DETERMINISTIC);
  }

  private List<Map<String, Object>> chunkMetadata(UUID documentId) {
    return jdbcTemplate.query(
        "SELECT metadata::text AS metadata FROM vector_store WHERE metadata->>'document_id' = ?",
        (rs, i) -> parseJson(rs.getString("metadata")),
        documentId.toString());
  }

  private List<UUID> chunkIds(UUID documentId) {
    return jdbcTemplate.query(
        "SELECT id FROM vector_store WHERE metadata->>'document_id' = ? ORDER BY id",
        (rs, i) -> UUID.fromString(rs.getString("id")),
        documentId.toString());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseJson(String json) {
    return new tools.jackson.databind.ObjectMapper().readValue(json, Map.class);
  }

  private static void writePdf(Path file, String title, LocalDate creationDate) throws IOException {
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
      PDDocumentInformation info = doc.getDocumentInformation();
      if (title != null) {
        info.setTitle(title);
      }
      if (creationDate != null) {
        Calendar calendar = new GregorianCalendar();
        calendar.setTime(Date.from(creationDate.atStartOfDay(ZoneId.systemDefault()).toInstant()));
        info.setCreationDate(calendar);
        info.setModificationDate(calendar);
      }
      doc.save(file.toFile());
    }
  }

  /** A DOCX whose Dokumentart stands in its first lines and nowhere else (#1263). */
  private static void writeDocxWithHead(Path file, String head, String body) throws IOException {
    try (XWPFDocument doc = new XWPFDocument()) {
      for (String text : List.of(head, body)) {
        XWPFParagraph paragraph = doc.createParagraph();
        paragraph.createRun().setText(text);
      }
      try (OutputStream out = Files.newOutputStream(file)) {
        doc.write(out);
      }
    }
  }

  private static void writePptx(Path file, String title, String body) throws IOException {
    try (XMLSlideShow show = new XMLSlideShow()) {
      XSLFSlide slide = show.createSlide();
      for (String text : List.of(title, body)) {
        XSLFTextBox box = slide.createTextBox();
        box.setText(text);
      }
      try (OutputStream out = Files.newOutputStream(file)) {
        show.write(out);
      }
    }
  }

  private static void writeDocx(Path file, String title, LocalDate modified) throws IOException {
    try (XWPFDocument doc = new XWPFDocument()) {
      doc.getProperties().getCoreProperties().setTitle(title);
      // OOXML core properties are W3CDTF in UTC; the reader resolves the day in UTC as well.
      Date date = Date.from(modified.atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
      doc.getProperties().getCoreProperties().setCreated(java.util.Optional.of(date));
      doc.getProperties().getCoreProperties().setModified(java.util.Optional.of(date));
      for (String text :
          List.of(
              "Die Frist beginnt mit Zugang des Bescheids.",
              "Eine Verlaengerung ist schriftlich zu beantragen.",
              "Der Antrag ist zu begruenden.")) {
        XWPFParagraph paragraph = doc.createParagraph();
        paragraph.createRun().setText(text);
      }
      try (OutputStream out = Files.newOutputStream(file)) {
        doc.write(out);
      }
    }
  }
}
