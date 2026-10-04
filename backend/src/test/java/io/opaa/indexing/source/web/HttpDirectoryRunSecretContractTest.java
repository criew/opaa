package io.opaa.indexing.source.web;

import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/** The web directory's run against a local autoindex listing with Basic auth. */
class HttpDirectoryRunSecretContractTest extends RunSecretContract {

  private static final int FILES = 5;
  private static final byte[] PDF =
      "%PDF-1.4\n%mock-pdf-body-for-magic-byte-detection".getBytes(StandardCharsets.UTF_8);

  private HttpServer server;

  @BeforeEach
  void serve() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    StringBuilder listing = new StringBuilder("<html><head><title>Index of /files/</title>");
    listing.append("</head><body><ul>");
    for (int i = 1; i <= FILES; i++) {
      listing.append("<li><a href=\"bericht-").append(i).append(".pdf\">x</a></li>");
      respond("/files/bericht-" + i + ".pdf", "application/pdf", PDF);
    }
    listing.append("</ul></body></html>");
    respond("/files/", "text/html", listing.toString().getBytes(StandardCharsets.UTF_8));
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void respond(String path, String contentType, byte[] body) {
    server.createContext(
        path,
        exchange -> {
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
        "leser:geheim",
        false,
        null);
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
