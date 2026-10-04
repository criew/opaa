package io.opaa.indexing.source.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.opaa.indexing.document.DocumentIngestResult;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.RunSecretContract;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryFolderService;
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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The web directory's run against a local autoindex listing with Basic auth, which refuses {@link
 * #refusedSecret()} with {@code 401}.
 */
class HttpDirectoryRunSecretContractTest extends RunSecretContract {

  private static final int FILES = 5;
  private static final byte[] PDF =
      "%PDF-1.4\n%mock-pdf-body-for-magic-byte-detection".getBytes(StandardCharsets.UTF_8);

  private HttpServer server;
  private final List<String> authorizations = new CopyOnWriteArrayList<>();
  private volatile String secret = "leser:geheim";
  private volatile boolean protectedFolder;

  @BeforeEach
  void serve() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    StringBuilder listing = new StringBuilder("<html><head><title>Index of /files/</title>");
    listing.append("</head><body><ul>");
    for (int i = 1; i <= FILES; i++) {
      listing.append("<li><a href=\"bericht-").append(i).append(".pdf\">x</a></li>");
      respond("/files/bericht-" + i + ".pdf", "application/pdf", () -> PDF);
    }
    String files = listing + "</ul></body></html>";
    String withFolder = listing + "<li><a href=\"intern/\">intern/</a></li></ul></body></html>";
    respond(
        "/files/",
        "text/html",
        () -> (protectedFolder ? withFolder : files).getBytes(StandardCharsets.UTF_8));
    respond("/files/intern/", "text/html", () -> null);
    server.start();
  }

  /** Without a secret the source asks for one; that stays the page's failure as before. */
  @Test
  void a401WithoutASecretSentFailsTheRunAsBeforeWithoutReportingARejection() throws Exception {
    secret = null;
    UUID jobId = UUID.randomUUID();

    run(template(), jobId, library());

    verify(jobService).failJob(eq(jobId), startsWith("HTTP 401 Unauthorized — check credentials."));
    verify(ingestService, never()).ingest(any(), any());
    assertThat(rejectionsReported()).isZero();
    assertThat(asksAfterRejection()).isZero();
  }

  /** A folder of its own realm below the start page refuses this account, not its secret. */
  @Test
  void a401BelowTheStartPageLeavesThatFolderOutWithoutReportingARejection() throws Exception {
    when(ingestService.ingest(any(), any())).thenReturn(DocumentIngestResult.PROCESSED);
    protectedFolder = true;
    UUID jobId = UUID.randomUUID();

    run(template(), jobId, library());

    verify(jobService, never()).failJob(eq(jobId), anyString());
    verify(jobService, never()).failJob(eq(jobId), anyString(), anyString());
    verify(ingestService, times(FILES)).ingest(any(), any());
    verifyNoInteractions(cleanupService);
    assertThat(rejectionsReported()).isZero();
    assertThat(asksAfterRejection()).isZero();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  /** Answers {@code body}, or {@code 401} without credentials, to the refused ones or to none. */
  private void respond(String path, String contentType, Supplier<byte[]> answer) {
    server.createContext(
        path,
        exchange -> {
          String authorization = exchange.getRequestHeaders().getFirst("Authorization");
          authorizations.add(String.valueOf(authorization));
          byte[] body = answer.get();
          if (body == null
              || authorization == null
              || ("Basic "
                      + Base64.getEncoder()
                          .encodeToString(refusedSecret().getBytes(StandardCharsets.UTF_8)))
                  .equals(authorization)) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
          }
          exchange.getResponseHeaders().set("Content-Type", contentType);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
  }

  @Override
  protected SourceSettings settings() {
    return new SourceSettings(
        null,
        "http://127.0.0.1:" + server.getAddress().getPort() + "/files/",
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
    return HttpDirectorySourceConnector.TYPE;
  }

  @Override
  protected int documents() {
    return FILES;
  }

  @Override
  protected void run(IndexingRunTemplate template, UUID jobId, KnowledgeLibrary library) {
    TargetAddressValidator validator = TargetAddressValidator.disabled();
    SourceRequestPolicy policy = SourceRequestPolicy.defaults();
    CrawlProperties crawl = new CrawlProperties(0, 0, 0);
    new UrlIndexingExecutor(
            new AutoindexCrawlerService(validator, crawl, policy),
            new BoundedDownloader(validator, policy),
            ingestService,
            documentRepository,
            crawl,
            mock(LibraryFolderService.class),
            policy,
            template,
            ProductionDocumentFormats.supportedFormats())
        .execute(jobId, library, runMode());
  }
}
