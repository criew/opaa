package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.opaa.api.dto.IndexingTriggerRequest;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryVisibility;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link RssFeedIndexingExecutor} against a local {@code
 * com.sun.net.httpserver.HttpServer} stub (#467) - never a real address, per the issue's acceptance
 * criteria. {@link FileProcessingService} is mocked here: this class's own job is the
 * feed/detail-page fetch, the change checks and the main-text extraction, all of which are
 * independent of how the shared processing chain later stores the result (that chain has its own
 * tests on {@link FileProcessingServiceTest}).
 */
class RssFeedIndexingExecutorTest {

  private HttpServer server;
  private String baseUrl;

  private FileProcessingService fileProcessingService;
  private IndexingJobService indexingJobService;
  private DocumentRepository documentRepository;
  private RssFeedStateRepository feedStateRepository;
  private RssFeedIndexingExecutor executor;

  private final KnowledgeLibrary library =
      KnowledgeLibrary.ownedByUser(
          UUID.randomUUID(),
          "Bibliothek",
          null,
          UUID.randomUUID(),
          LibraryVisibility.PRIVATE,
          false,
          false);

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

    fileProcessingService = mock(FileProcessingService.class);
    indexingJobService = mock(IndexingJobService.class);
    documentRepository = mock(DocumentRepository.class);
    feedStateRepository = mock(RssFeedStateRepository.class);
    when(feedStateRepository.findByFeedUrl(anyString())).thenReturn(Optional.empty());

    IndexingProperties properties =
        new IndexingProperties(
            null,
            0,
            0,
            0,
            0,
            null,
            new IndexingProperties.Rss(200, 10_000, 10_000, 0, "OPAA-Indexer/test", null));

    executor =
        new RssFeedIndexingExecutor(
            new RssFeedParser(),
            fileProcessingService,
            indexingJobService,
            documentRepository,
            feedStateRepository,
            properties);
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  private void serve(String path, int status, String contentType, String body) {
    server.createContext(
        path,
        exchange -> {
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", contentType);
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
  }

  private String feedXml(String... itemLinks) {
    StringBuilder items = new StringBuilder();
    for (String link : itemLinks) {
      items
          .append("<item><title>Titel</title><link>")
          .append(link)
          .append("</link><pubDate>Mon, 01 Jan 2024 10:00:00 GMT</pubDate></item>");
    }
    return "<rss version=\"2.0\"><channel><title>Feed</title>" + items + "</channel></rss>";
  }

  private void execute(String feedUrl) {
    var request = new IndexingTriggerRequest().libraryId(library.getId()).url(URI.create(feedUrl));
    executor.execute(UUID.randomUUID(), request, library);
  }

  @Test
  void positiveRun_processesEachEntryAndStripsBoilerplateFromMainText() {
    String detailHtml =
        "<html><body>"
            + "<nav>Navigation</nav><header>Kopf</header>"
            + "<main><article>Der eigentliche Artikeltext.</article></main>"
            + "<footer>Fuss</footer>"
            + "</body></html>";
    serve(
        "/feed.xml", 200, "application/rss+xml", feedXml(baseUrl + "/a.html", baseUrl + "/b.html"));
    serve("/a.html", 200, "text/html", detailHtml);
    serve("/b.html", 200, "text/html", detailHtml);
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    verify(fileProcessingService, timeout(2000))
        .processRssEntry(
            eq("Der eigentliche Artikeltext."),
            anyString(),
            eq(baseUrl + "/a.html"),
            any(),
            eq(library));
    verify(fileProcessingService, timeout(2000))
        .processRssEntry(
            eq("Der eigentliche Artikeltext."),
            anyString(),
            eq(baseUrl + "/b.html"),
            any(),
            eq(library));
    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(2), eq(0), eq(0));
  }

  @Test
  void feedNotModified_endsRunWithoutFetchingAnyDetailPage() {
    server.createContext(
        "/feed.xml",
        exchange -> {
          exchange.sendResponseHeaders(304, -1);
          exchange.close();
        });
    AtomicInteger detailPageHits = new AtomicInteger();
    server.createContext(
        "/a.html",
        exchange -> {
          detailPageHits.incrementAndGet();
          exchange.sendResponseHeaders(200, 0);
          exchange.close();
        });

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(0), eq(0), eq(0));
    assertThat(detailPageHits.get()).isZero();
    verify(fileProcessingService, never())
        .processRssEntry(anyString(), any(), anyString(), any(), any());
  }

  @Test
  void unchangedEntry_skipsTheDetailPageFetchEntirely() {
    serve("/feed.xml", 200, "application/rss+xml", feedXml(baseUrl + "/a.html"));
    AtomicInteger detailPageHits = new AtomicInteger();
    server.createContext(
        "/a.html",
        exchange -> {
          detailPageHits.incrementAndGet();
          exchange.sendResponseHeaders(200, 0);
          exchange.close();
        });
    Document existing = new Document("Titel", baseUrl + "/a.html", "text/html", 10L);
    existing.setStatus(DocumentStatus.INDEXED);
    existing.setLastModifiedRemote(java.time.Instant.parse("2024-01-01T10:00:00Z").toString());
    when(documentRepository.findByFilePath(baseUrl + "/a.html")).thenReturn(Optional.of(existing));

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(0), eq(0), eq(1));
    assertThat(detailPageHits.get()).isZero();
  }

  @Test
  void aRejectedDetailPageIsSkippedAndTheRunContinues() {
    serve(
        "/feed.xml",
        200,
        "application/rss+xml",
        feedXml(baseUrl + "/missing.html", baseUrl + "/ok.html"));
    serve("/missing.html", 404, "text/html", "not found");
    serve("/ok.html", 200, "text/html", "<html><body><main>Text</main></body></html>");
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(1));
  }

  @Test
  void aRejectedByRemote403DetailPageIsSkippedAndTheRunContinues() {
    serve(
        "/feed.xml",
        200,
        "application/rss+xml",
        feedXml(baseUrl + "/forbidden.html", baseUrl + "/ok.html"));
    serve("/forbidden.html", 403, "text/html", "denied");
    serve("/ok.html", 200, "text/html", "<html><body><main>Text</main></body></html>");
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(1));
  }

  @Test
  void fileSchemeLinkIsSkippedWithoutBeingFetched() {
    serve("/feed.xml", 200, "application/rss+xml", feedXml("file:///etc/passwd"));

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(0), eq(0), eq(1));
    verify(fileProcessingService, never())
        .processRssEntry(anyString(), any(), anyString(), any(), any());
  }

  @Test
  void feedExceedingTheSizeLimitFailsTheJobInstead() {
    IndexingProperties properties =
        new IndexingProperties(
            null, 0, 0, 0, 0, null, new IndexingProperties.Rss(200, 10, 10_000, 0, null, null));
    executor =
        new RssFeedIndexingExecutor(
            new RssFeedParser(),
            fileProcessingService,
            indexingJobService,
            documentRepository,
            feedStateRepository,
            properties);
    serve(
        "/feed.xml", 200, "application/rss+xml", feedXml(baseUrl + "/a.html", baseUrl + "/b.html"));

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000)).failJob(any(), anyString());
  }

  @Test
  void invalidXmlFeedFailsTheJobWithTheParsersGermanMessage() {
    serve("/feed.xml", 200, "application/rss+xml", "not xml at all &undefined;");

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000))
        .failJob(any(), eq("Der RSS-Feed konnte nicht gelesen werden: kein gültiges XML."));
  }

  @Test
  void entryCountBeyondTheConfiguredLimitIsTruncated() {
    IndexingProperties properties =
        new IndexingProperties(
            null, 0, 0, 0, 0, null, new IndexingProperties.Rss(1, 10_000, 10_000, 0, null, null));
    executor =
        new RssFeedIndexingExecutor(
            new RssFeedParser(),
            fileProcessingService,
            indexingJobService,
            documentRepository,
            feedStateRepository,
            properties);
    serve(
        "/feed.xml", 200, "application/rss+xml", feedXml(baseUrl + "/a.html", baseUrl + "/b.html"));
    serve("/a.html", 200, "text/html", "<html><body><main>Text</main></body></html>");
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000)).setTotalDocuments(any(), eq(1));
    verify(fileProcessingService, never())
        .processRssEntry(anyString(), any(), eq(baseUrl + "/b.html"), any(), any());
  }
}
