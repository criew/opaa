package io.opaa.indexing.source.rss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.attachment.AttachmentProperties;
import io.opaa.indexing.document.AttachmentIndexer;
import io.opaa.indexing.document.DocumentIngestResult;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.RunSecretContract;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryStorageQuotaService;
import io.opaa.knowledge.SourceType;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.SourceRequestPolicy;
import io.opaa.test.ProductionDocumentFormats;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The RSS feed's run against a local feed with Basic auth, one detail page per entry and two
 * attachments on the first; {@link #refusedSecret()} is refused with {@code 401}.
 */
class RssFeedRunSecretContractTest extends RunSecretContract {

  private static final int ENTRIES = 5;
  private static final byte[] PDF =
      "%PDF-1.4\n%mock-pdf-body-for-magic-byte-detection".getBytes(StandardCharsets.UTF_8);

  private HttpServer server;
  private final List<String> requested = new CopyOnWriteArrayList<>();
  private final List<String> authorizations = new CopyOnWriteArrayList<>();
  private volatile boolean discardOnFirstAttachment;
  private volatile String secret = "leser:geheim";
  private volatile String deniedPath;

  @BeforeEach
  void serve() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    StringBuilder items = new StringBuilder();
    for (int i = 1; i <= ENTRIES; i++) {
      items
          .append("<item><title>Meldung ")
          .append(i)
          .append("</title><link>")
          .append(base)
          .append("/meldung-")
          .append(i)
          .append(".html</link><pubDate>Mon, 01 Jan 2024 10:00:00 GMT</pubDate></item>");
      String attachments =
          i == 1
              ? "<a href=\"/anlage-1.pdf\">Anlage 1</a> <a href=\"/anlage-2.pdf\">Anlage 2</a>"
              : "";
      respond(
          "/meldung-" + i + ".html",
          "text/html",
          ("<html><body><main><article>Meldung "
                  + i
                  + ". "
                  + attachments
                  + "</article></main>"
                  + "</body></html>")
              .getBytes(StandardCharsets.UTF_8));
    }
    respond("/anlage-1.pdf", "application/pdf", PDF);
    respond("/anlage-2.pdf", "application/pdf", PDF);
    respond(
        "/feed.xml",
        "application/rss+xml",
        ("<rss version=\"2.0\"><channel><title>Feed</title>" + items + "</channel></rss>")
            .getBytes(StandardCharsets.UTF_8));
    server.start();
    Document entry = mock(Document.class);
    when(entry.getId()).thenReturn(UUID.randomUUID());
    when(documentRepository.findByLibraryIdAndFilePath(any(), any()))
        .thenAnswer(
            call ->
                call.<String>getArgument(1).endsWith(".html")
                    ? Optional.of(entry)
                    : Optional.empty());
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void respond(String path, String contentType, byte[] body) {
    server.createContext(
        path,
        exchange -> {
          requested.add(path);
          String authorization = exchange.getRequestHeaders().getFirst("Authorization");
          authorizations.add(String.valueOf(authorization));
          if (authorization == null
              || path.equals(deniedPath)
              || ("Basic "
                      + Base64.getEncoder()
                          .encodeToString(refusedSecret().getBytes(StandardCharsets.UTF_8)))
                  .equals(authorization)) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
          }
          if (path.equals("/anlage-1.pdf") && discardOnFirstAttachment) {
            discardNow();
          }
          exchange.getResponseHeaders().set("Content-Type", contentType);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
  }

  /** The header of every attachment is asked when it is sent, not when the list is built. */
  @Test
  void aSecretDiscardedWhileAnAttachmentLoadsEndsTheRunBeforeTheNextAttachment() throws Exception {
    when(ingestService.ingest(any(), any())).thenReturn(DocumentIngestResult.PROCESSED);
    discardOnFirstAttachment = true;
    UUID jobId = UUID.randomUUID();

    run(template(), jobId, library());

    verify(jobService).failJob(jobId, NOTICE, "NOT_CONNECTED");
    assertThat(requested).contains("/anlage-1.pdf").doesNotContain("/anlage-2.pdf");
    assertThat(asksAfterRefusal()).isPositive();
  }

  /** Without a secret the feed asks for one; that stays the run's failure as before. */
  @Test
  void a401WithoutASecretSentFailsTheRunAsBeforeWithoutReportingARejection() throws Exception {
    secret = null;
    UUID jobId = UUID.randomUUID();

    run(template(), jobId, library());

    verify(jobService).failJob(jobId, "Der RSS-Feed konnte nicht abgerufen werden: HTTP 401");
    assertThat(rejectionsReported()).isZero();
    assertThat(asksAfterRejection()).isZero();
  }

  /** A detail page of its own realm refuses this account, not the feed's secret. */
  @Test
  void a401OnADetailPageSkipsThatEntryWithoutReportingARejection() throws Exception {
    when(ingestService.ingest(any(), any())).thenReturn(DocumentIngestResult.PROCESSED);
    deniedPath = "/meldung-2.html";
    UUID jobId = UUID.randomUUID();

    run(template(), jobId, library());

    verify(jobService, never()).failJob(eq(jobId), anyString());
    verify(jobService, never()).failJob(eq(jobId), anyString(), anyString());
    assertThat(requested).contains("/meldung-2.html", "/meldung-3.html");
    assertThat(rejectionsReported()).isZero();
    assertThat(asksAfterRejection()).isZero();
  }

  @Override
  protected SourceSettings settings() {
    return new SourceSettings(
        null,
        "http://127.0.0.1:" + server.getAddress().getPort() + "/feed.xml",
        null,
        secret,
        false,
        null);
  }

  @Override
  protected String renewedSecret() {
    return "leser:erneuert";
  }

  @Override
  protected boolean sawRenewedSecret() {
    return authorizations.contains(
        "Basic "
            + Base64.getEncoder().encodeToString(renewedSecret().getBytes(StandardCharsets.UTF_8)));
  }

  @Override
  protected boolean usesRejectionSeam() {
    return true;
  }

  @Override
  protected String refusedSecret() {
    return "leser:falsch";
  }

  @Override
  protected SourceType type() {
    return RssFeedSourceConnector.TYPE;
  }

  @Override
  protected int documents() {
    return ENTRIES;
  }

  @Override
  protected IndexingRunMode runMode() {
    return IndexingRunMode.INCREMENTAL;
  }

  @Override
  protected void run(IndexingRunTemplate template, UUID jobId, KnowledgeLibrary library) {
    TargetAddressValidator validator = TargetAddressValidator.disabled();
    SourceRequestPolicy policy = SourceRequestPolicy.defaults();
    new RssFeedIndexingExecutor(
            new RssFeedParser(),
            ingestService,
            documentRepository,
            mock(RssFeedStateRepository.class),
            new AttachmentIndexer(
                new BoundedDownloader(validator, policy),
                ingestService,
                mock(LibraryStorageQuotaService.class),
                new AttachmentProperties(5, 0, 0),
                ProductionDocumentFormats.supportedFormats()),
            new RssFeedProperties(200, 10_000, 10_000, 0, null, null, 0, 0),
            validator,
            policy,
            template)
        .execute(jobId, library, runMode());
  }
}
