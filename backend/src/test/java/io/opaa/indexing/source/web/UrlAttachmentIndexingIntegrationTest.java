package io.opaa.indexing.source.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.IndexingJob;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.IndexingRunEventRepository;
import io.opaa.indexing.StaleDocumentCleanupService;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.organization.Organization;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.message.BodyPartBuilder;
import org.apache.james.mime4j.message.DefaultMessageWriter;
import org.apache.james.mime4j.message.MultipartBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end coverage of #1219 (ADR-0022 for HTTP_DIRECTORY): an {@code .eml} served from a web
 * directory has its attachments indexed as their own {@code Document} rows through the generalized
 * attachment path, and {@code cleanupVanished}'s attachment bookkeeping holds across changed,
 * unchanged and nested mails. Drives the real, Spring-wired {@link FileProcessingService} bean
 * graph against a loopback {@code com.sun.net.httpserver.HttpServer}; only the executor itself is
 * hand-built, so the crawler/downloader can use {@link TargetAddressValidator#disabled()} (the
 * loopback stub would otherwise be blocked) without a context-splitting property override.
 */
@io.opaa.test.OpaaIndexingIntegrationTest
class UrlAttachmentIndexingIntegrationTest {

  @Autowired private FileProcessingService fileProcessingService;
  @Autowired private IndexingJobService indexingJobService;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private IndexingRunEventRepository indexingRunEventRepository;
  @Autowired private LibraryStorageQuotaService storageQuotaService;
  @Autowired private StaleDocumentCleanupService staleDocumentCleanupService;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private io.opaa.indexing.VectorChunkStore vectorChunkStore;
  @Autowired private JdbcTemplate jdbcTemplate;

  private HttpServer server;
  private String baseUrl;
  private final Map<String, byte[]> servedFiles = new ConcurrentHashMap<>();

  private UUID userId;
  private KnowledgeLibrary library;
  private UrlIndexingExecutor executor;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/docs/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          byte[] body;
          String contentType;
          if (path.equals("/docs/")) {
            StringBuilder listing =
                new StringBuilder("<html><head><title>Index of /docs/</title></head><body><ul>");
            for (String name : servedFiles.keySet()) {
              listing
                  .append("<li><a href=\"")
                  .append(name)
                  .append("\">")
                  .append(name)
                  .append("</a></li>");
            }
            listing.append("</ul></body></html>");
            body = listing.toString().getBytes(StandardCharsets.UTF_8);
            contentType = "text/html";
          } else {
            body = servedFiles.get(path.substring("/docs/".length()));
            contentType = "message/rfc822";
            if (body == null) {
              exchange.sendResponseHeaders(404, -1);
              exchange.close();
              return;
            }
          }
          exchange.getResponseHeaders().set("Content-Type", contentType);
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
          exchange.close();
        });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

    userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'URL Attachment IT', now(), ?)",
        userId,
        "url-attachment-it-" + userId,
        "url-attachment-it-" + userId + "@example.com",
        Organization.DEFAULT_ID);
    library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Webverzeichnis",
                null,
                userId,
                LibraryVisibility.PRIVATE,
                false,
                DocumentSourceType.HTTP_DIRECTORY,
                null,
                baseUrl + "/docs/",
                null,
                null,
                false));

    TargetAddressValidator validator = TargetAddressValidator.disabled();
    executor =
        new UrlIndexingExecutor(
            new AutoindexCrawlerService(validator, new CrawlProperties(0, 0, 0)),
            new BoundedDownloader(validator),
            fileProcessingService,
            indexingJobService,
            documentRepository,
            indexingRunEventRepository,
            storageQuotaService,
            staleDocumentCleanupService,
            new CrawlProperties(0, 0, 0));
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
    // Library-scoped cleanup, deepest-first for fk_documents_parent (#1217: no TRUNCATE on shared
    // tables).
    List<Document> documents =
        documentRepository.findByLibraryIdAndSourceType(
            library.getId(), DocumentSourceType.HTTP_DIRECTORY);
    documents.stream()
        .sorted(
            java.util.Comparator.comparingInt((Document d) -> d.getFilePath().length()).reversed())
        .forEach(
            document -> {
              vectorChunkStore.deleteByDocumentId(document.getId());
              documentRepository.delete(document);
            });
    jdbcTemplate.update(
        "DELETE FROM indexing_run_events WHERE job_id IN"
            + " (SELECT id FROM indexing_jobs WHERE library_id = ?)",
        library.getId());
    jdbcTemplate.update("DELETE FROM indexing_jobs WHERE library_id = ?", library.getId());
    libraryRepository.deleteById(library.getId());
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
  }

  /** Runs one full executor pass - synchronous, since the executor is called directly here. */
  private void run() {
    IndexingJob job = indexingJobService.startJob(library.getId(), Organization.DEFAULT_ID);
    executor.execute(job.getId(), library);
  }

  @Test
  void anEmlFromAWebDirectoryIndexesItsAttachmentAsItsOwnDocument() throws Exception {
    servedFiles.put("anfrage.eml", emlWithTextAttachment("Bitte pruefen.", "Anhangsinhalt."));

    run();

    List<Document> documents =
        documentRepository.findByLibraryIdAndSourceType(
            library.getId(), DocumentSourceType.HTTP_DIRECTORY);
    assertThat(documents).hasSize(2);
    Document mail =
        documents.stream().filter(d -> d.getParentDocumentId() == null).findFirst().orElseThrow();
    Document attachment =
        documents.stream().filter(d -> d.getParentDocumentId() != null).findFirst().orElseThrow();
    assertThat(mail.getFilePath()).isEqualTo(baseUrl + "/docs/anfrage.eml");
    assertThat(mail.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    assertThat(attachment.getParentDocumentId()).isEqualTo(mail.getId());
    assertThat(attachment.getFileName()).isEqualTo("anlage.txt");
    assertThat(attachment.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    // ADR-0022, Entscheidung 2: the attachment's file_path embeds the parent's URL.
    assertThat(attachment.getFilePath()).startsWith(mail.getFilePath() + "/");
    // #1130 Befund 2, structurally fixed on this path too: the attachment's chunks carry its own
    // pipeline's id (Tika fallback for plain text), the mail's carry the mail pipeline's.
    assertThat(chunkPipelineIds(attachment.getId())).containsExactly("tika-fallback");
    assertThat(chunkPipelineIds(mail.getId())).containsExactly("email");
  }

  @Test
  void attachmentBookkeepingAcrossChangedAndUnchangedMailsAndNestedDepth() throws Exception {
    // Depth 2 (mail-in-mail): outer mail -> inner mail -> grandchild attachment.
    servedFiles.put("weiterleitung.eml", nestedEml());

    run();

    List<Document> afterFirstRun =
        documentRepository.findByLibraryIdAndSourceType(
            library.getId(), DocumentSourceType.HTTP_DIRECTORY);
    assertThat(afterFirstRun).hasSize(3);
    Document outer =
        afterFirstRun.stream()
            .filter(d -> d.getParentDocumentId() == null)
            .findFirst()
            .orElseThrow();
    Document inner =
        afterFirstRun.stream()
            .filter(d -> outer.getId().equals(d.getParentDocumentId()))
            .findFirst()
            .orElseThrow();
    Document grandchild =
        afterFirstRun.stream()
            .filter(d -> inner.getId().equals(d.getParentDocumentId()))
            .findFirst()
            .orElseThrow();
    assertThat(grandchild.getFilePath()).startsWith(inner.getFilePath() + "/");

    // Nachtragsfall: a second run over the unchanged mail (same bytes, checksum-skipped) must
    // preserve the whole attachment chain from the database instead of cleaning it up as
    // vanished.
    run();
    assertThat(
            documentRepository.findByLibraryIdAndSourceType(
                library.getId(), DocumentSourceType.HTTP_DIRECTORY))
        .hasSize(3);

    // Changed mail: the re-served mail no longer carries the inner mail - the removed attachment
    // (and its own child) fall away with the next run, while the mail itself stays.
    servedFiles.put(
        "weiterleitung.eml", emlWithTextAttachment("Geaenderter Text.", "Neuer Anhang."));
    run();

    List<Document> afterChange =
        documentRepository.findByLibraryIdAndSourceType(
            library.getId(), DocumentSourceType.HTTP_DIRECTORY);
    assertThat(afterChange).hasSize(2);
    assertThat(afterChange)
        .noneMatch(d -> d.getId().equals(inner.getId()) || d.getId().equals(grandchild.getId()));
    assertThat(afterChange).anyMatch(d -> d.getId().equals(outer.getId()));
  }

  private byte[] emlWithTextAttachment(String bodyText, String attachmentText) throws Exception {
    Message message =
        Message.Builder.of()
            .setSubject("Anfrage")
            .setFrom("a@example.org")
            .setTo("b@example.org")
            .setBody(
                MultipartBuilder.create("mixed")
                    .addTextPart(bodyText, StandardCharsets.UTF_8)
                    .addBodyPart(
                        BodyPartBuilder.create()
                            .setBody(attachmentText.getBytes(StandardCharsets.UTF_8), "text/plain")
                            .setContentDisposition("attachment", "anlage.txt"))
                    .build())
            .build();
    return DefaultMessageWriter.asBytes(message);
  }

  private byte[] nestedEml() throws Exception {
    Message inner =
        Message.Builder.of()
            .setSubject("Innen")
            .setFrom("c@example.org")
            .setTo("d@example.org")
            .setBody(
                MultipartBuilder.create("mixed")
                    .addTextPart("Innerer Text.", StandardCharsets.UTF_8)
                    .addBodyPart(
                        BodyPartBuilder.create()
                            .setBody("Enkel-Anhang.".getBytes(StandardCharsets.UTF_8), "text/plain")
                            .setContentDisposition("attachment", "enkel.txt"))
                    .build())
            .build();
    byte[] innerBytes = DefaultMessageWriter.asBytes(inner);
    Message outer =
        Message.Builder.of()
            .setSubject("Aussen")
            .setFrom("a@example.org")
            .setTo("b@example.org")
            .setBody(
                MultipartBuilder.create("mixed")
                    .addTextPart("Aeusserer Text.", StandardCharsets.UTF_8)
                    .addBodyPart(
                        BodyPartBuilder.create()
                            .setBody(innerBytes, "message/rfc822")
                            .setContentDisposition("attachment", "innen.eml"))
                    .build())
            .build();
    return DefaultMessageWriter.asBytes(outer);
  }

  /** The distinct {@code pipeline_id} chunk metadata values stored for {@code documentId}. */
  private List<String> chunkPipelineIds(UUID documentId) {
    return jdbcTemplate.queryForList(
        "SELECT DISTINCT metadata->>'pipeline_id' FROM vector_store WHERE"
            + " metadata->>'document_id' = ?",
        String.class,
        documentId.toString());
  }
}
