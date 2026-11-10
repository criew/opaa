package io.opaa.indexing.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opaa.FakeEmbeddingModel;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.format.DocumentService;
import io.opaa.format.file.pdf.PdfOcrDocumentFormat;
import io.opaa.indexing.chunk.EmbeddingIndexReconciler;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.llm.textrecognition.TextRecognitionEndpoints;
import io.opaa.organization.Organization;
import io.opaa.permission.AssetGrant;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.test.OpaaPropertyVariantIntegrationTest;
import io.opaa.test.OpaaTestDirectory;
import io.opaa.test.OwnLibraryFixtures;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A scan through the whole indexing against a stub docling-serve (ADR-0069): recognised and cut
 * with frames, its recognition stored without images and deleted with the document; a failure of
 * the service leaves it waiting, a refusal locks it until a curator releases the lock.
 */
@OpaaPropertyVariantIntegrationTest
class TextRecognitionIngestIntegrationTest {

  private static final Path classTempDir =
      OpaaTestDirectory.subdirectory("text-recognition-ingest");

  @Autowired private DocumentIngestService documentIngestService;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TextRecognitionEndpoints endpoints;
  @Autowired private TextRecognitionBlocks blocks;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private LibraryAccessService accessService;
  @Autowired private EmbeddingIndexReconciler reconciler;

  private final AtomicInteger conversions = new AtomicInteger();
  private volatile int answerStatus = 200;
  private HttpServer server;
  private UUID userId;
  private CurrentUser owner;
  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() throws IOException {
    // neighbours in this signature drop the vector table; the chunks of a scan need it
    jdbcTemplate.execute(
        "CREATE TABLE IF NOT EXISTS vector_store (id uuid PRIMARY KEY DEFAULT gen_random_uuid(),"
            + " content text, metadata json, embedding vector("
            + FakeEmbeddingModel.DIMENSIONS
            + "))");
    reconciler.reconcile();
    userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'OCR IT', now(), ?, ?)",
        userId,
        "ocr-it-" + userId,
        "ocr-it-" + userId + "@example.com",
        SystemRole.USER.name(),
        Organization.DEFAULT_ID);
    owner = CurrentUser.of(userId, Organization.DEFAULT_ID, SystemRole.USER, "OCR IT");
    library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(Organization.DEFAULT_ID, "Scans", null, userId));
    grantRepository.save(
        AssetGrant.forUser(
            KnowledgeLibrary.ASSET_TYPE,
            library.getId(),
            Organization.DEFAULT_ID,
            userId,
            AssetRole.EDITOR,
            null,
            userId));
    accessService.invalidateLibrary(library.getId());
    String document = fixture();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/docling/v1/convert/file",
        exchange -> {
          conversions.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          if (answerStatus != 200) {
            respond(exchange, answerStatus, "{\"detail\":\"nein\"}");
            return;
          }
          respond(
              exchange,
              200,
              "{\"status\":\"success\",\"errors\":[],\"document\":{\"json_content\":"
                  + document
                  + "}}");
        });
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
    jdbcTemplate.update("DELETE FROM text_recognition_model");
    jdbcTemplate.update(
        "DELETE FROM audit_log WHERE event_type = 'TEXT_RECOGNITION_BLOCK_RELEASED'"
            + " AND object_id = ?",
        library.getId().toString());
    endpoints.invalidateAll();
    ownLibraryFixtures.removeLibraries(library.getId());
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
  }

  @Test
  void aScanIsRecognisedCutWithFramesAndItsRecognitionGoesWithTheDocument() throws IOException {
    switchOn();

    DocumentIngestResult result = ingest("satzung.pdf");

    assertThat(result).isEqualTo(DocumentIngestResult.PROCESSED);
    assertThat(conversions).hasValue(1);
    Document document = onlyDocument();
    assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    List<Map<String, Object>> chunks =
        jdbcTemplate.queryForList(
            "SELECT content, metadata->>'pipeline_id' AS pipeline,"
                + " metadata->'anchor'->'regions' AS regions,"
                + " metadata->'anchor'->'textOrigin'->>'connectionType' AS origin,"
                + " metadata->>'routing_extension' AS routing"
                + " FROM vector_store WHERE metadata->>'document_id' = ?",
            document.getId().toString());
    assertThat(chunks).hasSize(3);
    assertThat(chunks)
        .allSatisfy(
            chunk -> {
              assertThat(chunk.get("pipeline")).isEqualTo(PdfOcrDocumentFormat.ID);
              assertThat(chunk.get("origin")).isEqualTo("DOCLING_SERVE");
              assertThat(String.valueOf(chunk.get("regions"))).contains("\"page\"");
              assertThat(chunk.get("routing")).isNull();
            });
    String stored =
        jdbcTemplate.queryForObject(
            "SELECT recognized_content FROM document_text_recognition WHERE document_id = ?",
            String.class,
            document.getId());
    assertThat(stored).contains("Beglaubigung").doesNotContain("base64");

    assertThat(ingest("satzung.pdf"))
        .as("an unchanged scan is not sent again")
        .isEqualTo(DocumentIngestResult.SKIPPED);
    assertThat(conversions).hasValue(1);

    documentRepository.delete(document);
    assertThat(recognitionRows(document.getId())).isZero();
  }

  @Test
  void aFailureOfTheServiceLeavesTheScanRejectedAndWaitingWithoutALock() throws IOException {
    switchOn();
    answerStatus = 503;

    ingest("satzung.pdf");

    Document document = onlyDocument();
    assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
    assertThat(document.getErrorMessage()).isEqualTo(DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE);
    assertThat(recognitionRows(document.getId())).isZero();
    assertThat(blocks.counts(library.getId())).isEqualTo(new TextRecognitionBlocks.Counts(1, 0));
  }

  @Test
  void aRefusalLocksTheScanUntilItIsReleased() throws IOException {
    switchOn();
    answerStatus = 422;

    ingest("satzung.pdf");

    Document document = onlyDocument();
    assertThat(blocks.counts(library.getId())).isEqualTo(new TextRecognitionBlocks.Counts(0, 1));
    var locked = blocks.list(library.getId(), owner);
    assertThat(locked)
        .singleElement()
        .satisfies(
            block -> {
              assertThat(block.documentId()).isEqualTo(document.getId());
              assertThat(block.fileName()).isEqualTo("satzung.pdf");
              assertThat(block.reason())
                  .isEqualTo("Der Dienst hat das Dokument abgelehnt (HTTP 422)");
              assertThat(block.connectionType()).isEqualTo("DOCLING_SERVE");
            });

    blocks.release(library.getId(), document.getId(), owner);
    blocks.release(library.getId(), document.getId(), owner);

    assertThat(recognitionRows(document.getId())).isZero();
    assertThat(blocks.counts(library.getId())).isEqualTo(new TextRecognitionBlocks.Counts(1, 0));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE event_type ="
                    + " 'TEXT_RECOGNITION_BLOCK_RELEASED' AND object_id = ?",
                Integer.class,
                library.getId().toString()))
        .as("only the release that lifted a lock is recorded")
        .isEqualTo(1);
  }

  @Test
  void withoutTheRoleAScanIsRejectedAsBeforeAndNothingIsSent() throws IOException {
    ingest("satzung.pdf");

    Document document = onlyDocument();
    assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
    assertThat(document.getErrorMessage()).isEqualTo(DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE);
    assertThat(conversions).hasValue(0);
    assertThat(blocks.counts(library.getId())).isEqualTo(TextRecognitionBlocks.Counts.NOT_IN_FORCE);
  }

  private void switchOn() {
    jdbcTemplate.update(
        "INSERT INTO text_recognition_model (id, enabled, connection_type, base_url, updated_at)"
            + " VALUES (1, true, 'DOCLING_SERVE', ?, now())",
        "http://127.0.0.1:" + server.getAddress().getPort() + "/docling");
    endpoints.invalidateAll();
  }

  private DocumentIngestResult ingest(String name) throws IOException {
    Path file = classTempDir.resolve(library.getId() + "-" + name);
    if (!Files.exists(file)) {
      try (PDDocument pdf = new PDDocument()) {
        pdf.addPage(new PDPage());
        pdf.addPage(new PDPage());
        pdf.save(file.toFile());
      }
    }
    return documentIngestService.ingest(
        DocumentIngest.localFile(library, file).fileName(name).build(), null);
  }

  private Document onlyDocument() {
    List<Document> documents = documentRepository.findByLibraryId(library.getId());
    assertThat(documents).hasSize(1);
    return documents.getFirst();
  }

  private int recognitionRows(UUID documentId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM document_text_recognition WHERE document_id = ?",
        Integer.class,
        documentId);
  }

  private static String fixture() throws IOException {
    try (InputStream in =
        TextRecognitionIngestIntegrationTest.class.getResourceAsStream(
            "/text-recognition/docling-gebuehrensatzung.json")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
