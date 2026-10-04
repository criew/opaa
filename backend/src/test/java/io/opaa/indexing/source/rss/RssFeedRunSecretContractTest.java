package io.opaa.indexing.source.rss;

import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.attachment.AttachmentProperties;
import io.opaa.indexing.document.AttachmentIndexer;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.RunSecretContract;
import io.opaa.indexing.source.SourceSettings;
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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/** The RSS feed's run against a local feed with Basic auth and one detail page per entry. */
class RssFeedRunSecretContractTest extends RunSecretContract {

  private static final int ENTRIES = 5;

  private HttpServer server;

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
      respond(
          "/meldung-" + i + ".html",
          "text/html",
          "<html><body><main><article>Meldung " + i + ".</article></main></body></html>");
    }
    respond(
        "/feed.xml",
        "application/rss+xml",
        "<rss version=\"2.0\"><channel><title>Feed</title>" + items + "</channel></rss>");
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void respond(String path, String contentType, String body) {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    server.createContext(
        path,
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", contentType);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
  }

  @Override
  protected SourceSettings settings() {
    return new SourceSettings(
        null,
        "http://127.0.0.1:" + server.getAddress().getPort() + "/feed.xml",
        null,
        "leser:geheim",
        false,
        null);
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
