package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIndexingIntegrationTest;
import io.opaa.test.OpaaIndexingTestDirectory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The order in which a changed document's chunks are exchanged (#1268): the previous chunks survive
 * until the new version has actually parsed and chunked, and both chunk stores ({@code
 * vector_store}, {@code chunk_full_text}) always agree on the result.
 *
 * <p>Two ways a parse can fail are covered, because the caller must handle both identically: a
 * pipeline that reports {@code PARSE_FAILED} without throwing (the corrupt PDF below) and one that
 * lets an exception escape (the Tika fallback, mocked here through {@link DocumentService}). {@code
 * processRssEntry} has no parse step of its own - it is handed already-extracted text - so only its
 * empty and success cases exist to test.
 */
// Same single @MockitoBean DocumentService as FileProcessingServiceIntegrationTest, so both classes
// share one context: the mock is what makes an unparseable Tika document reproducible at all.
@OpaaIndexingIntegrationTest
class ChunkReplacementOrderIntegrationTest {

  private static final Path classTempDir =
      OpaaIndexingTestDirectory.subdirectory("chunk-replacement-order");

  private static final String FIRST_TEXT =
      "Die erste Fassung dieses Dokuments beschreibt das Verfahren zur Aktenfuehrung.";
  private static final String SECOND_TEXT =
      "Die zweite Fassung dieses Dokuments beschreibt das Verfahren zur Vorgangsbearbeitung.";

  @Autowired private FileProcessingService fileProcessingService;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @MockitoBean private DocumentService documentService;

  private KnowledgeLibrary targetLibrary;

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store, chunk_full_text, chunk_full_text_skip");
    documentRepository.deleteAll();

    jdbcTemplate.update(
        "DELETE FROM knowledge_libraries WHERE owner_user_id IN (SELECT id FROM users WHERE"
            + " email = 'file-processing-it@example.com')");
    jdbcTemplate.update("DELETE FROM users WHERE email = 'file-processing-it@example.com'");
    UUID userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', 'file-processing-it@example.com',"
            + " 'File Processing IT User', now(), ?, ?)",
        userId,
        "chunk-replacement-it-" + userId,
        SystemRole.SYSTEM_ADMIN.name(),
        Organization.DEFAULT_ID);

    targetLibrary =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Zielbibliothek",
                null,
                userId,
                LibraryVisibility.PRIVATE,
                false));
  }

  @Test
  void changedFileWhosePipelineThrowsKeepsItsPreviousChunks() throws IOException {
    Path file = classTempDir.resolve("wirft.txt");
    Files.writeString(file, FIRST_TEXT);
    when(documentService.parseDocument(file))
        .thenReturn(List.of(new org.springframework.ai.document.Document(FIRST_TEXT)));
    assertThat(fileProcessingService.processFile(file, targetLibrary))
        .isEqualTo(FileProcessingResult.PROCESSED);
    UUID documentId = onlyDocumentId();
    long previousVectorChunks = vectorChunks(documentId);
    long previousFullTextChunks = fullTextChunks(documentId);
    assertThat(previousVectorChunks).isPositive();
    assertThat(previousFullTextChunks).isEqualTo(previousVectorChunks);

    Files.writeString(file, SECOND_TEXT);
    when(documentService.parseDocument(file)).thenThrow(new IllegalStateException("Reader kaputt"));

    assertThatThrownBy(() -> fileProcessingService.processFile(file, targetLibrary))
        .isInstanceOf(IllegalStateException.class);

    Document doc = documentRepository.findById(documentId).orElseThrow();
    assertThat(doc.getStatus()).isEqualTo(DocumentStatus.FAILED);
    assertThat(doc.getChunkCount()).isEqualTo((int) previousVectorChunks);
    assertThat(vectorChunks(documentId)).isEqualTo(previousVectorChunks);
    assertThat(fullTextChunks(documentId)).isEqualTo(previousFullTextChunks);
    assertThat(vectorContents(documentId)).allMatch(content -> content.contains("erste Fassung"));
  }

  @Test
  void changedPdfThatCannotBeParsedKeepsItsPreviousChunks() throws IOException {
    Path file = classTempDir.resolve("kaputt.pdf");
    writePdf(file);
    assertThat(fileProcessingService.processFile(file, targetLibrary))
        .isEqualTo(FileProcessingResult.PROCESSED);
    UUID documentId = onlyDocumentId();
    long previousVectorChunks = vectorChunks(documentId);
    assertThat(previousVectorChunks).isPositive();

    // Keeps the %PDF magic bytes, so routing still picks PdfDocumentPipeline - which cannot load
    // the body and reports PARSE_FAILED rather than throwing.
    Files.write(file, "%PDF-1.7\nnicht wirklich ein PDF".getBytes(StandardCharsets.UTF_8));

    assertThat(fileProcessingService.processFile(file, targetLibrary))
        .isEqualTo(FileProcessingResult.FAILED);

    Document doc = documentRepository.findById(documentId).orElseThrow();
    assertThat(doc.getStatus()).isEqualTo(DocumentStatus.FAILED);
    assertThat(doc.getChunkCount()).isEqualTo((int) previousVectorChunks);
    assertThat(vectorChunks(documentId)).isEqualTo(previousVectorChunks);
    assertThat(fullTextChunks(documentId)).isEqualTo(previousVectorChunks);
  }

  @Test
  void changedFileThatIsNowEmptyLosesItsChunks() throws IOException {
    Path file = classTempDir.resolve("geleert.txt");
    Files.writeString(file, FIRST_TEXT);
    when(documentService.parseDocument(file))
        .thenReturn(List.of(new org.springframework.ai.document.Document(FIRST_TEXT)));
    assertThat(fileProcessingService.processFile(file, targetLibrary))
        .isEqualTo(FileProcessingResult.PROCESSED);
    UUID documentId = onlyDocumentId();
    assertThat(vectorChunks(documentId)).isPositive();

    Files.writeString(file, "");
    when(documentService.parseDocument(file)).thenReturn(List.of());

    assertThat(fileProcessingService.processFile(file, targetLibrary))
        .isEqualTo(FileProcessingResult.FAILED);

    assertThat(documentRepository.findById(documentId).orElseThrow().getStatus())
        .isEqualTo(DocumentStatus.FAILED);
    assertThat(vectorChunks(documentId)).isZero();
    assertThat(fullTextChunks(documentId)).isZero();
  }

  @Test
  void changedPdfWithoutATextLayerLosesItsChunks() throws IOException {
    Path file = classTempDir.resolve("scan.pdf");
    writePdf(file);
    assertThat(fileProcessingService.processFile(file, targetLibrary))
        .isEqualTo(FileProcessingResult.PROCESSED);
    UUID documentId = onlyDocumentId();
    assertThat(vectorChunks(documentId)).isPositive();

    writeTextlessPdf(file);

    assertThat(fileProcessingService.processFile(file, targetLibrary))
        .isEqualTo(FileProcessingResult.NO_EXTRACTABLE_TEXT);

    Document doc = documentRepository.findById(documentId).orElseThrow();
    assertThat(doc.getStatus()).isEqualTo(DocumentStatus.FAILED);
    assertThat(doc.getErrorMessage()).isEqualTo(DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE);
    assertThat(vectorChunks(documentId)).isZero();
    assertThat(fullTextChunks(documentId)).isZero();
  }

  @Test
  void changedFileThatParsesSuccessfullyReplacesItsChunksWithoutDuplicates() throws IOException {
    Path file = classTempDir.resolve("ersetzt.txt");
    Files.writeString(file, FIRST_TEXT);
    when(documentService.parseDocument(file))
        .thenReturn(List.of(new org.springframework.ai.document.Document(FIRST_TEXT)));
    assertThat(fileProcessingService.processFile(file, targetLibrary))
        .isEqualTo(FileProcessingResult.PROCESSED);
    UUID documentId = onlyDocumentId();
    long previousVectorChunks = vectorChunks(documentId);

    Files.writeString(file, SECOND_TEXT);
    when(documentService.parseDocument(file))
        .thenReturn(List.of(new org.springframework.ai.document.Document(SECOND_TEXT)));

    assertThat(fileProcessingService.processFile(file, targetLibrary))
        .isEqualTo(FileProcessingResult.PROCESSED);

    Document doc = documentRepository.findById(documentId).orElseThrow();
    assertThat(doc.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    assertThat(vectorChunks(documentId)).isEqualTo(doc.getChunkCount());
    assertThat(fullTextChunks(documentId)).isEqualTo(doc.getChunkCount());
    assertThat(vectorContents(documentId))
        .hasSize((int) previousVectorChunks)
        .allMatch(content -> content.contains("zweite Fassung"));
  }

  @Test
  void changedUrlDocumentThatCannotBeParsedKeepsItsPreviousChunks() throws IOException {
    Path file = classTempDir.resolve("url-kaputt.pdf");
    writePdf(file);
    assertThat(processUrl(file, "url-kaputt.pdf")).isEqualTo(FileProcessingResult.PROCESSED);
    UUID documentId = onlyDocumentId();
    long previousVectorChunks = vectorChunks(documentId);
    assertThat(previousVectorChunks).isPositive();

    Files.write(file, "%PDF-1.7\nnicht wirklich ein PDF".getBytes(StandardCharsets.UTF_8));

    assertThat(processUrl(file, "url-kaputt.pdf")).isEqualTo(FileProcessingResult.FAILED);

    Document doc = documentRepository.findById(documentId).orElseThrow();
    assertThat(doc.getStatus()).isEqualTo(DocumentStatus.FAILED);
    assertThat(doc.getChunkCount()).isEqualTo((int) previousVectorChunks);
    assertThat(vectorChunks(documentId)).isEqualTo(previousVectorChunks);
    assertThat(fullTextChunks(documentId)).isEqualTo(previousVectorChunks);
  }

  @Test
  void changedUrlDocumentThatIsNowEmptyLosesItsChunks() throws IOException {
    Path file = classTempDir.resolve("url-geleert.txt");
    Files.writeString(file, FIRST_TEXT);
    when(documentService.parseDocument(file))
        .thenReturn(List.of(new org.springframework.ai.document.Document(FIRST_TEXT)));
    assertThat(processUrl(file, "url-geleert.txt")).isEqualTo(FileProcessingResult.PROCESSED);
    UUID documentId = onlyDocumentId();
    assertThat(vectorChunks(documentId)).isPositive();

    Files.writeString(file, "");
    when(documentService.parseDocument(file)).thenReturn(List.of());

    assertThat(processUrl(file, "url-geleert.txt")).isEqualTo(FileProcessingResult.FAILED);

    assertThat(vectorChunks(documentId)).isZero();
    assertThat(fullTextChunks(documentId)).isZero();
  }

  @Test
  void changedUrlDocumentThatParsesSuccessfullyReplacesItsChunksWithoutDuplicates()
      throws IOException {
    Path file = classTempDir.resolve("url-ersetzt.txt");
    Files.writeString(file, FIRST_TEXT);
    when(documentService.parseDocument(file))
        .thenReturn(List.of(new org.springframework.ai.document.Document(FIRST_TEXT)));
    assertThat(processUrl(file, "url-ersetzt.txt")).isEqualTo(FileProcessingResult.PROCESSED);
    UUID documentId = onlyDocumentId();

    Files.writeString(file, SECOND_TEXT);
    when(documentService.parseDocument(file))
        .thenReturn(List.of(new org.springframework.ai.document.Document(SECOND_TEXT)));

    assertThat(processUrl(file, "url-ersetzt.txt")).isEqualTo(FileProcessingResult.PROCESSED);

    Document doc = documentRepository.findById(documentId).orElseThrow();
    assertThat(vectorChunks(documentId)).isEqualTo(doc.getChunkCount());
    assertThat(fullTextChunks(documentId)).isEqualTo(doc.getChunkCount());
    assertThat(vectorContents(documentId)).allMatch(content -> content.contains("zweite Fassung"));
  }

  @Test
  void changedRssEntryWithoutUsableTextLosesItsChunks() {
    assertThat(
            fileProcessingService.processRssEntry(
                FIRST_TEXT, "Meldung", "https://example.test/1", null, targetLibrary))
        .isEqualTo(FileProcessingResult.PROCESSED);
    UUID documentId = onlyDocumentId();
    assertThat(vectorChunks(documentId)).isPositive();

    assertThat(
            fileProcessingService.processRssEntry(
                "x", "Meldung", "https://example.test/1", null, targetLibrary))
        .isEqualTo(FileProcessingResult.NO_EXTRACTABLE_TEXT);

    assertThat(vectorChunks(documentId)).isZero();
    assertThat(fullTextChunks(documentId)).isZero();
  }

  @Test
  void changedRssEntryThatParsesSuccessfullyReplacesItsChunksWithoutDuplicates() {
    assertThat(
            fileProcessingService.processRssEntry(
                FIRST_TEXT, "Meldung", "https://example.test/2", null, targetLibrary))
        .isEqualTo(FileProcessingResult.PROCESSED);
    UUID documentId = onlyDocumentId();

    assertThat(
            fileProcessingService.processRssEntry(
                SECOND_TEXT, "Meldung", "https://example.test/2", null, targetLibrary))
        .isEqualTo(FileProcessingResult.PROCESSED);

    Document doc = documentRepository.findById(documentId).orElseThrow();
    assertThat(vectorChunks(documentId)).isEqualTo(doc.getChunkCount());
    assertThat(fullTextChunks(documentId)).isEqualTo(doc.getChunkCount());
    assertThat(vectorContents(documentId)).allMatch(content -> content.contains("zweite Fassung"));
  }

  private FileProcessingResult processUrl(Path localFile, String fileName) throws IOException {
    return fileProcessingService.processUrlFile(
        localFile,
        fileName,
        "https://example.test/dateien/" + fileName,
        null,
        Files.size(localFile),
        targetLibrary);
  }

  private UUID onlyDocumentId() {
    List<Document> documents = documentRepository.findAll();
    assertThat(documents).hasSize(1);
    return documents.getFirst().getId();
  }

  private long vectorChunks(UUID documentId) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM vector_store WHERE metadata->>'document_id' = ?",
            Long.class,
            documentId.toString());
    return count == null ? 0L : count;
  }

  private long fullTextChunks(UUID documentId) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM chunk_full_text WHERE document_id = ?", Long.class, documentId);
    return count == null ? 0L : count;
  }

  private List<String> vectorContents(UUID documentId) {
    return jdbcTemplate.queryForList(
        "SELECT content FROM vector_store WHERE metadata->>'document_id' = ?",
        String.class,
        documentId.toString());
  }

  private static void writePdf(Path file) throws IOException {
    try (PDDocument doc = new PDDocument()) {
      for (String text : List.of(FIRST_TEXT, "Zweiter Absatz zur Aktenfuehrung im Amt.")) {
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
      doc.save(file.toFile());
    }
  }

  /** A structurally valid PDF whose single page carries no text layer at all - a scan. */
  private static void writeTextlessPdf(Path file) throws IOException {
    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage(PDRectangle.A4));
      doc.save(file.toFile());
    }
  }
}
