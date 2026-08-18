package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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
import java.util.concurrent.atomic.AtomicReference;
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

    executor =
        newExecutor(
            new IndexingProperties.Rss(
                200, 10_000, 10_000, 0, "OPAA-Indexer/test", null, null, 0, 0));
  }

  private RssFeedIndexingExecutor newExecutor(IndexingProperties.Rss rss) {
    IndexingProperties properties = new IndexingProperties(null, 0, 0, 0, 0, null, rss);
    return new RssFeedIndexingExecutor(
        new RssFeedParser(),
        fileProcessingService,
        indexingJobService,
        documentRepository,
        feedStateRepository,
        new UrlFileDownloader(),
        properties);
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  private void serve(String path, int status, String contentType, String body) {
    serveBytes(path, status, contentType, body.getBytes(StandardCharsets.UTF_8));
  }

  private void serveBytes(String path, int status, String contentType, byte[] bytes) {
    server.createContext(
        path,
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", contentType);
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
  }

  /** Like {@link #serve}, but with an {@code ETag} the feed-state persistence tests assert on. */
  private void serveFeedWithEtag(String path, String body, String etag) {
    server.createContext(
        path,
        exchange -> {
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/rss+xml");
          exchange.getResponseHeaders().set("ETag", etag);
          exchange.sendResponseHeaders(200, bytes.length);
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
  void boilerplateIsStrippedEvenWithoutAMainElement_fallsBackToBody() {
    // #490 review, finding 5: without a <main>/<article>, the selector matches nothing and the
    // executor falls back to <body> - this is the only case that actually exercises the
    // nav/header/footer .remove() call, since a matched <main> would exclude siblings anyway.
    String detailHtml =
        "<html><body>"
            + "<nav>Navigation</nav><header>Kopf</header>"
            + "<div>Eigentlicher Inhalt</div>"
            + "<footer>Fuss</footer>"
            + "</body></html>";
    serve("/feed.xml", 200, "application/rss+xml", feedXml(baseUrl + "/a.html"));
    serve("/a.html", 200, "text/html", detailHtml);
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    verify(fileProcessingService, timeout(2000))
        .processRssEntry(
            eq("Eigentlicher Inhalt"), anyString(), eq(baseUrl + "/a.html"), any(), eq(library));
  }

  @Test
  void detailPageCharsetFromContentTypeIsHonouredInsteadOfHardcodedUtf8() {
    // #490 review, finding 1: an ISO-8859-1 page hardcoded as UTF-8 turns "Behörde für
    // Straßenbau" into U+FFFD replacement characters, silently, while still ending up INDEXED.
    String html = "<html><body><main>Behörde für Straßenbau</main></body></html>";
    byte[] isoBytes = html.getBytes(StandardCharsets.ISO_8859_1);
    serve("/feed.xml", 200, "application/rss+xml", feedXml(baseUrl + "/a.html"));
    serveBytes("/a.html", 200, "text/html; charset=ISO-8859-1", isoBytes);
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    verify(fileProcessingService, timeout(2000))
        .processRssEntry(
            eq("Behörde für Straßenbau"), anyString(), eq(baseUrl + "/a.html"), any(), eq(library));
  }

  @Test
  void detailPageWithNonHtmlContentTypeIsSkippedAndTheRunContinues() {
    // #490 review, finding 2: a <link> pointing straight at a PDF must never be pushed through
    // Jsoup and indexed as garbled binary text.
    serve(
        "/feed.xml",
        200,
        "application/rss+xml",
        feedXml(baseUrl + "/doc.pdf", baseUrl + "/ok.html"));
    serve("/doc.pdf", 200, "application/pdf", "%PDF-1.4 not real content");
    serve("/ok.html", 200, "text/html", "<html><body><main>Text</main></body></html>");
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(1));
    verify(fileProcessingService, never())
        .processRssEntry(anyString(), any(), eq(baseUrl + "/doc.pdf"), any(), any());
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
  void conditionalGetHeadersAreSentWhenFeedStateExists() {
    // #490 review, finding 6: the previous 304 test never actually exercised sending
    // If-None-Match/If-Modified-Since, because the repository stub returned empty.
    RssFeedState state =
        new RssFeedState(baseUrl + "/feed.xml", "\"abc123\"", "Mon, 01 Jan 2024 00:00:00 GMT");
    when(feedStateRepository.findByFeedUrl(baseUrl + "/feed.xml")).thenReturn(Optional.of(state));
    AtomicReference<String> ifNoneMatch = new AtomicReference<>();
    AtomicReference<String> ifModifiedSince = new AtomicReference<>();
    server.createContext(
        "/feed.xml",
        exchange -> {
          ifNoneMatch.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
          ifModifiedSince.set(exchange.getRequestHeaders().getFirst("If-Modified-Since"));
          exchange.sendResponseHeaders(304, -1);
          exchange.close();
        });

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(0), eq(0), eq(0));
    assertThat(ifNoneMatch.get()).isEqualTo("\"abc123\"");
    assertThat(ifModifiedSince.get()).isEqualTo("Mon, 01 Jan 2024 00:00:00 GMT");
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
    existing.setLibraryId(library.getId());
    when(documentRepository.findByFilePath(baseUrl + "/a.html")).thenReturn(Optional.of(existing));

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(0), eq(0), eq(1));
    assertThat(detailPageHits.get()).isZero();
  }

  @Test
  void anEntryMovedToAnotherLibraryIsNotTreatedAsUnchanged() {
    // #490 review, finding 8: mirrors FileProcessingService#processRssEntry's own library check -
    // without it, a library move never took effect for an entry whose pubDate is unchanged,
    // because this check runs before the detail page (and processRssEntry) is ever reached.
    serve("/feed.xml", 200, "application/rss+xml", feedXml(baseUrl + "/a.html"));
    serve("/a.html", 200, "text/html", "<html><body><main>Text</main></body></html>");
    Document existing = new Document("Titel", baseUrl + "/a.html", "text/html", 10L);
    existing.setStatus(DocumentStatus.INDEXED);
    existing.setLastModifiedRemote(java.time.Instant.parse("2024-01-01T10:00:00Z").toString());
    existing.setLibraryId(UUID.randomUUID()); // a different library than the run's target
    when(documentRepository.findByFilePath(baseUrl + "/a.html")).thenReturn(Optional.of(existing));
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    verify(fileProcessingService, timeout(2000))
        .processRssEntry(anyString(), anyString(), eq(baseUrl + "/a.html"), any(), eq(library));
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
    executor = newExecutor(new IndexingProperties.Rss(200, 10, 10_000, 0, null, null, null, 0, 0));
    serve(
        "/feed.xml", 200, "application/rss+xml", feedXml(baseUrl + "/a.html", baseUrl + "/b.html"));

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000)).failJob(any(), anyString());
  }

  @Test
  void detailPageExceedingTheSizeLimitIsSkippedAndTheRunContinues() {
    executor = newExecutor(new IndexingProperties.Rss(200, 10_000, 10, 0, null, null, null, 0, 0));
    serve("/feed.xml", 200, "application/rss+xml", feedXml(baseUrl + "/a.html"));
    serve(
        "/a.html",
        200,
        "text/html",
        "<html><body><main>" + "x".repeat(500) + "</main></body></html>");

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(0), eq(0), eq(1));
    verify(fileProcessingService, never())
        .processRssEntry(anyString(), any(), anyString(), any(), any());
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
    executor =
        newExecutor(new IndexingProperties.Rss(1, 10_000, 10_000, 0, null, null, null, 0, 0));
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

  // --- #490 review, finding 3: feed-state persistence must not hide deferred entries ---

  @Test
  void feedStateIsNotPersistedWhenAnEntryWasRejectedByTheRemoteEnd() {
    serveFeedWithEtag("/feed.xml", feedXml(baseUrl + "/forbidden.html"), "\"etag-rejected\"");
    serve("/forbidden.html", 403, "text/html", "denied");

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(0), eq(0), eq(1));
    verify(feedStateRepository, never()).save(any());
  }

  @Test
  void feedStateIsNotPersistedWhenEntriesWereTruncatedByTheMaxEntriesLimit() {
    executor =
        newExecutor(new IndexingProperties.Rss(1, 10_000, 10_000, 0, null, null, null, 0, 0));
    serveFeedWithEtag(
        "/feed.xml", feedXml(baseUrl + "/a.html", baseUrl + "/b.html"), "\"etag-truncated\"");
    serve("/a.html", 200, "text/html", "<html><body><main>Text</main></body></html>");
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(0));
    verify(feedStateRepository, never()).save(any());
  }

  @Test
  void feedStateIsPersistedWhenEveryEntrySucceeded() {
    serveFeedWithEtag("/feed.xml", feedXml(baseUrl + "/a.html"), "\"etag-success\"");
    serve("/a.html", 200, "text/html", "<html><body><main>Text</main></body></html>");
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(0));
    verify(feedStateRepository, timeout(2000))
        .save(argThat(state -> "\"etag-success\"".equals(state.getEtag())));
  }

  // --- #468: attachments ---

  @Test
  void genericProfileFindsAndIndexesAPdfAttachmentInTheMainContent() throws IOException {
    executor =
        newExecutor(
            new IndexingProperties.Rss(
                200, 10_000, 10_000, 0, null, null, AttachmentProfile.GENERIC, 10, 10_000));
    String detailHtml =
        "<html><body><main>Text"
            + "<a href=\""
            + baseUrl
            + "/downloads/anlage.pdf\">Anlage</a></main></body></html>";
    serve("/feed.xml", 200, "application/rss+xml", feedXml(baseUrl + "/a.html"));
    serve("/a.html", 200, "text/html", detailHtml);
    serveBytes(
        "/downloads/anlage.pdf",
        200,
        "application/pdf",
        "%PDF-1.4 not real content".getBytes(StandardCharsets.UTF_8));
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);
    when(fileProcessingService.processUrlFile(
            any(), anyString(), anyString(), any(), anyLong(), eq(library), any(), anyString()))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    verify(fileProcessingService, timeout(2000))
        .processUrlFile(
            any(),
            eq("anlage.pdf"),
            eq(baseUrl + "/downloads/anlage.pdf"),
            any(),
            anyLong(),
            eq(library),
            eq(DocumentSourceType.RSS_FEED),
            eq(baseUrl + "/a.html"));
  }

  @Test
  void aLinkToAForeignHostIsNeverTreatedAsAnAttachment() throws IOException {
    executor =
        newExecutor(
            new IndexingProperties.Rss(
                200, 10_000, 10_000, 0, null, null, AttachmentProfile.GENERIC, 10, 10_000));
    String detailHtml =
        "<html><body><main>Text"
            + "<a href=\"https://anderes-beispiel.gov/anlage.pdf\">Fremd</a></main></body></html>";
    serve("/feed.xml", 200, "application/rss+xml", feedXml(baseUrl + "/a.html"));
    serve("/a.html", 200, "text/html", detailHtml);
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(0));
    verify(fileProcessingService, never())
        .processUrlFile(any(), any(), any(), any(), anyLong(), any(), any(), any());
  }

  @Test
  void gsbProfileFindsAQueryParameterAttachmentAndDerivesAFileNameFromContentType()
      throws IOException {
    // Generic reproduction of the Government Site Builder pattern (#468) - a fictional
    // example.gov-style address, never a real institution's.
    executor =
        newExecutor(
            new IndexingProperties.Rss(
                200, 10_000, 10_000, 0, null, null, AttachmentProfile.GSB, 10, 10_000));
    String detailHtml =
        "<html><body><main>Text"
            + "<a href=\""
            + baseUrl
            + "/service/mein-dokument?__blob=publicationFile\">Herunterladen</a></main></body></html>";
    serve("/feed.xml", 200, "application/rss+xml", feedXml(baseUrl + "/a.html"));
    serve("/a.html", 200, "text/html", detailHtml);
    serveBytes(
        "/service/mein-dokument",
        200,
        "application/pdf",
        "%PDF-1.4 not real content".getBytes(StandardCharsets.UTF_8));
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);
    when(fileProcessingService.processUrlFile(
            any(), anyString(), anyString(), any(), anyLong(), eq(library), any(), anyString()))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    verify(fileProcessingService, timeout(2000))
        .processUrlFile(
            any(),
            eq("mein-dokument.pdf"),
            eq(baseUrl + "/service/mein-dokument?__blob=publicationFile"),
            any(),
            anyLong(),
            eq(library),
            eq(DocumentSourceType.RSS_FEED),
            eq(baseUrl + "/a.html"));
  }

  @Test
  void withoutAConfiguredProfileGenericIsUsed() throws IOException {
    // The default in IndexingProperties.Rss's compact constructor, exercised end to end.
    executor =
        newExecutor(
            new IndexingProperties.Rss(200, 10_000, 10_000, 0, null, null, null, 10, 10_000));
    String detailHtml =
        "<html><body><main>Text"
            + "<a href=\""
            + baseUrl
            + "/downloads/anlage.pdf\">Anlage</a></main></body></html>";
    serve("/feed.xml", 200, "application/rss+xml", feedXml(baseUrl + "/a.html"));
    serve("/a.html", 200, "text/html", detailHtml);
    serveBytes(
        "/downloads/anlage.pdf",
        200,
        "application/pdf",
        "%PDF-1.4 not real content".getBytes(StandardCharsets.UTF_8));
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);
    when(fileProcessingService.processUrlFile(
            any(), anyString(), anyString(), any(), anyLong(), eq(library), any(), anyString()))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    verify(fileProcessingService, timeout(2000))
        .processUrlFile(
            any(),
            eq("anlage.pdf"),
            eq(baseUrl + "/downloads/anlage.pdf"),
            any(),
            anyLong(),
            eq(library),
            eq(DocumentSourceType.RSS_FEED),
            eq(baseUrl + "/a.html"));
  }

  @Test
  void theSameAttachmentLinkedFromTwoEntriesIsProcessedForEachEntryItAppearsOn()
      throws IOException {
    // #468 acceptance criteria: the same attachment linked from two entries becomes one document
    // - identity is by the attachment's own URL (file_path), the same deduplication
    // FileProcessingService#processUrlFile already applies for HTTP_DIRECTORY files (see
    // FileProcessingServiceTest#processUrlFileSkipsUnchangedDocument). This test exercises the
    // executor's side of that: both entries' detail pages link the identical attachment URL.
    executor =
        newExecutor(
            new IndexingProperties.Rss(
                200, 10_000, 10_000, 0, null, null, AttachmentProfile.GENERIC, 10, 10_000));
    String detailHtml =
        "<html><body><main>Text"
            + "<a href=\""
            + baseUrl
            + "/downloads/geteilte-anlage.pdf\">Anlage</a></main></body></html>";
    serve(
        "/feed.xml", 200, "application/rss+xml", feedXml(baseUrl + "/a.html", baseUrl + "/b.html"));
    serve("/a.html", 200, "text/html", detailHtml);
    serve("/b.html", 200, "text/html", detailHtml);
    serveBytes(
        "/downloads/geteilte-anlage.pdf",
        200,
        "application/pdf",
        "%PDF-1.4 not real content".getBytes(StandardCharsets.UTF_8));
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);
    when(fileProcessingService.processUrlFile(
            any(), anyString(), anyString(), any(), anyLong(), eq(library), any(), anyString()))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    verify(fileProcessingService, timeout(2000))
        .processUrlFile(
            any(),
            anyString(),
            eq(baseUrl + "/downloads/geteilte-anlage.pdf"),
            any(),
            anyLong(),
            eq(library),
            eq(DocumentSourceType.RSS_FEED),
            eq(baseUrl + "/a.html"));
    verify(fileProcessingService, timeout(2000))
        .processUrlFile(
            any(),
            anyString(),
            eq(baseUrl + "/downloads/geteilte-anlage.pdf"),
            any(),
            anyLong(),
            eq(library),
            eq(DocumentSourceType.RSS_FEED),
            eq(baseUrl + "/b.html"));
  }

  @Test
  void aFailedAttachmentDownloadDoesNotAbortTheEntryOrTheRun() throws IOException {
    executor =
        newExecutor(
            new IndexingProperties.Rss(
                200, 10_000, 10_000, 0, null, null, AttachmentProfile.GENERIC, 10, 10_000));
    String detailHtml =
        "<html><body><main>Text"
            + "<a href=\""
            + baseUrl
            + "/downloads/fehlt.pdf\">Anlage</a></main></body></html>";
    serve("/feed.xml", 200, "application/rss+xml", feedXml(baseUrl + "/a.html"));
    serve("/a.html", 200, "text/html", detailHtml);
    serve("/downloads/fehlt.pdf", 404, "text/html", "not found");
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    // The entry itself still counts as processed - only the attachment failed.
    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(0));
    verify(fileProcessingService, never())
        .processUrlFile(any(), any(), any(), any(), anyLong(), any(), any(), any());
  }

  @Test
  void anAttachmentExceedingTheSizeLimitIsSkippedWithoutFailingTheEntry() throws IOException {
    executor =
        newExecutor(
            new IndexingProperties.Rss(
                200, 10_000, 10_000, 0, null, null, AttachmentProfile.GENERIC, 10, 10));
    String detailHtml =
        "<html><body><main>Text"
            + "<a href=\""
            + baseUrl
            + "/downloads/gross.pdf\">Anlage</a></main></body></html>";
    serve("/feed.xml", 200, "application/rss+xml", feedXml(baseUrl + "/a.html"));
    serve("/a.html", 200, "text/html", detailHtml);
    serveBytes(
        "/downloads/gross.pdf",
        200,
        "application/pdf",
        "x".repeat(500).getBytes(StandardCharsets.UTF_8));
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(0));
    verify(fileProcessingService, never())
        .processUrlFile(any(), any(), any(), any(), anyLong(), any(), any(), any());
  }

  @Test
  void attachmentsBeyondTheConfiguredLimitAreNotProcessed() throws IOException {
    executor =
        newExecutor(
            new IndexingProperties.Rss(
                200, 10_000, 10_000, 0, null, null, AttachmentProfile.GENERIC, 1, 10_000));
    String detailHtml =
        "<html><body><main>Text"
            + "<a href=\""
            + baseUrl
            + "/downloads/erste.pdf\">Erste</a>"
            + "<a href=\""
            + baseUrl
            + "/downloads/zweite.pdf\">Zweite</a></main></body></html>";
    serve("/feed.xml", 200, "application/rss+xml", feedXml(baseUrl + "/a.html"));
    serve("/a.html", 200, "text/html", detailHtml);
    serveBytes(
        "/downloads/erste.pdf", 200, "application/pdf", "erste".getBytes(StandardCharsets.UTF_8));
    serveBytes(
        "/downloads/zweite.pdf", 200, "application/pdf", "zweite".getBytes(StandardCharsets.UTF_8));
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);
    when(fileProcessingService.processUrlFile(
            any(), anyString(), anyString(), any(), anyLong(), eq(library), any(), anyString()))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    verify(fileProcessingService, timeout(2000))
        .processUrlFile(
            any(),
            eq("erste.pdf"),
            eq(baseUrl + "/downloads/erste.pdf"),
            any(),
            anyLong(),
            eq(library),
            eq(DocumentSourceType.RSS_FEED),
            eq(baseUrl + "/a.html"));
    verify(fileProcessingService, never())
        .processUrlFile(
            any(),
            eq("zweite.pdf"),
            eq(baseUrl + "/downloads/zweite.pdf"),
            any(),
            anyLong(),
            eq(library),
            any(),
            any());
  }
}
