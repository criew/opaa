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
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.ProxyAndCredentials;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
  private IndexingRunEventRepository indexingRunEventRepository;
  private LibraryStorageQuotaService storageQuotaService;
  private RssFeedIndexingExecutor executor;

  // #478: sourceUrl is mutated in place per test via execute(String) below
  // (updateSourceConfiguration)
  // rather than replacing the reference, so every eq(library) verification below - written against
  // this one field - still matches after the URL changes.
  private final KnowledgeLibrary library =
      KnowledgeLibrary.ownedByUser(
          UUID.randomUUID(),
          "Bibliothek",
          null,
          UUID.randomUUID(),
          LibraryVisibility.PRIVATE,
          false,
          DocumentSourceType.RSS_FEED,
          null,
          "https://example.com/feed.xml",
          null,
          null,
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
    when(feedStateRepository.findByLibraryIdAndFeedUrl(any(), anyString()))
        .thenReturn(Optional.empty());
    indexingRunEventRepository = mock(IndexingRunEventRepository.class);
    storageQuotaService = mock(LibraryStorageQuotaService.class);

    executor =
        newExecutor(
            new IndexingProperties.Rss(
                200, 10_000, 10_000, 0, "OPAA-Indexer/test", null, null, 0, 0));
  }

  private RssFeedIndexingExecutor newExecutor(IndexingProperties.Rss rss) {
    IndexingProperties properties =
        new IndexingProperties(null, 0, 0, 0, null, rss, null, null, null, 0);
    // Target validation is exercised on its own dedicated stand (TargetAddressValidatorTest,
    // RssFeedIndexingExecutorTargetValidationTest) - disabled here since every stub server this
    // class talks to is deliberately loopback (com.sun.net.httpserver.HttpServer, #467's own
    // acceptance criteria), which an enabled validator would reject by default.
    TargetAddressValidator targetAddressValidator = TargetAddressValidator.disabled();
    return new RssFeedIndexingExecutor(
        new RssFeedParser(),
        fileProcessingService,
        indexingJobService,
        documentRepository,
        feedStateRepository,
        new BoundedDownloader(targetAddressValidator),
        properties,
        indexingRunEventRepository,
        targetAddressValidator,
        storageQuotaService);
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
    library.updateSourceConfiguration(null, feedUrl, null, null, false);
    executor.execute(UUID.randomUUID(), library);
  }

  /**
   * Like {@link #execute(String)}, but also configures the library's own
   * sourceProxy/sourceCredentials (#505).
   */
  private void execute(String feedUrl, String proxy, String credentials) {
    library.updateSourceConfiguration(null, feedUrl, proxy, credentials, false);
    executor.execute(UUID.randomUUID(), library);
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
    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(2), eq(0), eq(0), eq(2));
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

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(1), eq(1));
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

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(0), eq(0), eq(0), eq(0));
    assertThat(detailPageHits.get()).isZero();
    verify(fileProcessingService, never())
        .processRssEntry(anyString(), any(), anyString(), any(), any());
  }

  @Test
  void conditionalGetHeadersAreSentWhenFeedStateExists() {
    // #490 review, finding 6: the previous 304 test never actually exercised sending
    // If-None-Match/If-Modified-Since, because the repository stub returned empty.
    RssFeedState state =
        new RssFeedState(
            library.getId(), baseUrl + "/feed.xml", "\"abc123\"", "Mon, 01 Jan 2024 00:00:00 GMT");
    when(feedStateRepository.findByLibraryIdAndFeedUrl(library.getId(), baseUrl + "/feed.xml"))
        .thenReturn(Optional.of(state));
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

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(0), eq(0), eq(0), eq(0));
    assertThat(ifNoneMatch.get()).isEqualTo("\"abc123\"");
    assertThat(ifModifiedSince.get()).isEqualTo("Mon, 01 Jan 2024 00:00:00 GMT");
  }

  @Test
  void aStateSavedForAnotherLibraryIsNeverSentAsConditionalHeadersForThisOne() {
    // #646, PR #665 review "should" finding 2: the actual regression guard for the read path this
    // issue fixed - RssFeedState is now looked up by (libraryId, feedUrl), not feedUrl alone.
    // feedStateRepository is a plain mock here, so stubbing findByLibraryIdAndFeedUrl for a
    // *different* UUID than library.getId() (the setUp() stub for library.getId() itself still
    // returns Optional.empty()) is exactly what a real, library-scoped repository would also
    // return: no row for this library, regardless of what another library's row holds for the
    // same feedUrl. Before #646 this executor called the equivalent of
    // feedStateRepository.findByFeedUrl(feedUrl) - unaware of *which* library was running - so it
    // would have found the foreign library's ETag here and sent it, ending the run with a false
    // 304 instead of processing the entry below.
    RssFeedState foreignLibraryState =
        new RssFeedState(
            UUID.randomUUID(),
            baseUrl + "/feed.xml",
            "\"etag-from-another-library\"",
            "Mon, 01 Jan 2024 00:00:00 GMT");
    when(feedStateRepository.findByLibraryIdAndFeedUrl(any(), eq(baseUrl + "/feed.xml")))
        .thenAnswer(
            invocation ->
                invocation.getArgument(0, UUID.class).equals(foreignLibraryState.getLibraryId())
                    ? Optional.of(foreignLibraryState)
                    : Optional.empty());
    AtomicReference<String> ifNoneMatch = new AtomicReference<>();
    AtomicReference<String> ifModifiedSince = new AtomicReference<>();
    server.createContext(
        "/feed.xml",
        exchange -> {
          ifNoneMatch.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
          ifModifiedSince.set(exchange.getRequestHeaders().getFirst("If-Modified-Since"));
          byte[] bytes = feedXml(baseUrl + "/a.html").getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/rss+xml");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    serve("/a.html", 200, "text/html", "<html><body><main>Text</main></body></html>");
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(0), eq(1));
    assertThat(ifNoneMatch.get()).isNull();
    assertThat(ifModifiedSince.get()).isNull();
    verify(fileProcessingService)
        .processRssEntry(anyString(), anyString(), eq(baseUrl + "/a.html"), any(), eq(library));
  }

  @Test
  void unchangedEntryWithAttachmentsAlreadyIndexedSkipsTheDetailPageFetchEntirely() {
    // #492 review, finding 1: the cheap path only stays cheap once attachments already exist for
    // this entry in this run's own library - existsBySourceEntryUrlAndLibraryId(true) is exactly
    // that case.
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
    when(documentRepository.findByLibraryIdAndFilePath(library.getId(), baseUrl + "/a.html"))
        .thenReturn(Optional.of(existing));
    when(documentRepository.existsBySourceEntryUrlAndLibraryId(
            baseUrl + "/a.html", library.getId()))
        .thenReturn(true);

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(0), eq(0), eq(1), eq(0));
    assertThat(detailPageHits.get()).isZero();
  }

  @Test
  void unchangedEntryWithoutAttachmentsYetFetchesTheDetailPageAndBackfillsThem()
      throws IOException {
    // #492 review, finding 1: an entry indexed before attachment support existed must still get
    // its attachments backfilled - existsBySourceEntryUrlAndLibraryId(false) is that case, and the
    // entry's own pubDate stays unchanged (its main text is never reprocessed), only the
    // attachment is new.
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
    Document existing = new Document("Titel", baseUrl + "/a.html", "text/html", 10L);
    existing.setStatus(DocumentStatus.INDEXED);
    existing.setLastModifiedRemote(java.time.Instant.parse("2024-01-01T10:00:00Z").toString());
    existing.setLibraryId(library.getId());
    when(documentRepository.findByLibraryIdAndFilePath(library.getId(), baseUrl + "/a.html"))
        .thenReturn(Optional.of(existing));
    when(documentRepository.existsBySourceEntryUrlAndLibraryId(
            baseUrl + "/a.html", library.getId()))
        .thenReturn(false);
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
    // The entry's own main text was never reprocessed - only its attachment was backfilled.
    verify(fileProcessingService, never())
        .processRssEntry(anyString(), any(), eq(baseUrl + "/a.html"), any(), any());
    // #518: the backfilled attachment still adds to documentsIndexedTotal even though the entry
    // itself counts as skipped (unchanged), not processed.
    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(0), eq(0), eq(1), eq(1));
  }

  @Test
  void anotherLibraryAlreadyHavingTheAttachmentDoesNotSuppressThisLibrarysOwnBackfill()
      throws IOException {
    // #877 review, finding 1: existsBySourceEntryUrlAndLibraryId is scoped to this run's own
    // library - another library already holding an attachment document for the same entry URL
    // must not suppress this library's own backfill. Stubbing a different library id to answer
    // true (while this run's own library answers false, as it genuinely has none yet) makes the
    // test fail loudly if the executor ever queries the wrong library id.
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
    Document existing = new Document("Titel", baseUrl + "/a.html", "text/html", 10L);
    existing.setStatus(DocumentStatus.INDEXED);
    existing.setLastModifiedRemote(java.time.Instant.parse("2024-01-01T10:00:00Z").toString());
    existing.setLibraryId(library.getId());
    when(documentRepository.findByLibraryIdAndFilePath(library.getId(), baseUrl + "/a.html"))
        .thenReturn(Optional.of(existing));
    when(documentRepository.existsBySourceEntryUrlAndLibraryId(
            baseUrl + "/a.html", library.getId()))
        .thenReturn(false);
    when(documentRepository.existsBySourceEntryUrlAndLibraryId(
            eq(baseUrl + "/a.html"), argThat(id -> !id.equals(library.getId()))))
        .thenReturn(true);
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
  void anEntryAlreadyIndexedIntoAnotherLibraryDoesNotSuppressProcessingForThisLibrary() {
    // #877 (Epic #826, Befund B6): isUnchanged's lookup is scoped to the run's own target
    // library (findByLibraryIdAndFilePath) - an entry another library already indexed under the
    // same URL is simply never found here, so its unchanged pubDate there cannot suppress
    // processing here, the same outcome the pre-#877 library-equality check used to guarantee.
    serve("/feed.xml", 200, "application/rss+xml", feedXml(baseUrl + "/a.html"));
    serve("/a.html", 200, "text/html", "<html><body><main>Text</main></body></html>");
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    verify(documentRepository).findByLibraryIdAndFilePath(library.getId(), baseUrl + "/a.html");
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

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(1), eq(1));
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

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(1), eq(1));
    // #513 (motivating BMF case, PR #604 review nit d): a bot-protection rejection is not just
    // counted as skipped - it becomes its own REJECTED IndexingRunEvent, without which nobody can
    // tell 19 skipped-because-rejected entries apart from any other reason for a lower count than
    // the feed offered.
    verify(indexingRunEventRepository, timeout(2000))
        .save(
            argThat(
                event ->
                    event.getCategory() == IndexingEventCategory.REJECTED
                        && (baseUrl + "/forbidden.html").equals(event.getReference())
                        // Must never leak the raw HTTP status/exception text alone - a German,
                        // human-readable reason instead (#513 acceptance criteria).
                        && event.getMessage() != null
                        && event.getMessage().contains("abgewiesen")));
  }

  @Test
  void anEntryOverTheLibraryStorageQuotaIsSkippedAndRecordedAsARejectedEvent() {
    // #119: the connector run protocol (#604) must show why a document stopped being added, not
    // just count it as skipped - QUOTA_EXCEEDED becomes its own REJECTED event with the exact
    // wording LibraryStorageQuotaService produces.
    serve("/feed.xml", 200, "application/rss+xml", feedXml(baseUrl + "/over-quota.html"));
    serve("/over-quota.html", 200, "text/html", "<html><body><main>Text</main></body></html>");
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.QUOTA_EXCEEDED);
    when(storageQuotaService.quotaExceededMessage(library.getId()))
        .thenReturn("Speicherkontingent der Bibliothek erschöpft (10,0 GB von 10,0 GB belegt)");

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(0), eq(0), eq(1), eq(0));
    String expectedMessage =
        "Speicherkontingent der Bibliothek erschöpft (10,0 GB von 10,0 GB belegt)";
    verify(indexingRunEventRepository, timeout(2000))
        .save(
            argThat(
                event ->
                    event.getCategory() == IndexingEventCategory.REJECTED
                        && (baseUrl + "/over-quota.html").equals(event.getReference())
                        && expectedMessage.equals(event.getMessage())));
  }

  @Test
  void aFailedEventWriteNeverPreventsTheRunFromCompleting() {
    // #513, PR #604 review finding 2: a DB hiccup while writing the protocol must never leave the
    // job stuck RUNNING - uk_indexing_jobs_library_running (migration 028) would then permanently
    // block every future run of this library. IndexingRunEventRecorder must swallow this itself.
    serve("/feed.xml", 200, "application/rss+xml", feedXml(baseUrl + "/forbidden.html"));
    serve("/forbidden.html", 403, "text/html", "denied");
    when(indexingRunEventRepository.save(any()))
        .thenThrow(new RuntimeException("simulated DB hiccup"));

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(0), eq(0), eq(1), eq(0));
  }

  @Test
  void fileSchemeLinkIsSkippedWithoutBeingFetched() {
    serve("/feed.xml", 200, "application/rss+xml", feedXml("file:///etc/passwd"));

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(0), eq(0), eq(1), eq(0));
    verify(fileProcessingService, never())
        .processRssEntry(anyString(), any(), anyString(), any(), any());
  }

  @Test
  void anEntryWithASyntacticallyInvalidLinkIsSkippedAndTheRunContinues() {
    // #651: isHttpOrHttps only checks the scheme prefix - an http(s)-prefixed link with an
    // embedded space is still syntactically invalid and makes URI.create(entryUrl), called deep
    // inside fetchDetailPage, throw IllegalArgumentException. Before this fix, that exception was
    // caught by none of processEntry's catch clauses and propagated out to execute()'s outer catch
    // (Exception e), ending the *entire* run - even though a second, perfectly valid entry
    // (/ok.html
    // here) was still waiting to be processed.
    serve(
        "/feed.xml",
        200,
        "application/rss+xml",
        feedXml(baseUrl + "/a b.html", baseUrl + "/ok.html"));
    serve("/ok.html", 200, "text/html", "<html><body><main>Text</main></body></html>");
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    // completeJob(jobId, documentsProcessed, documentsFailed, documentsSkipped,
    // documentsIndexedTotal): 1 processed (/ok.html), 0 failed, 1 skipped (the invalid link), 1
    // document indexed in total.
    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(1), eq(1));
    verify(fileProcessingService, timeout(2000))
        .processRssEntry(anyString(), anyString(), eq(baseUrl + "/ok.html"), any(), eq(library));
    verify(indexingRunEventRepository, timeout(2000))
        .save(
            argThat(
                event ->
                    event.getCategory() == IndexingEventCategory.REJECTED
                        && (baseUrl + "/a b.html").equals(event.getReference())));
  }

  @Test
  void anEntryLinkWithAHostUriCannotParseIsSkippedAndTheRunContinues() {
    // PR #664 review, finding 1a: URI.create itself accepts a link whose host it cannot parse
    // (e.g. one containing an underscore) without throwing at all - isValidUri's original
    // URI.create(url) call therefore let this link straight through, both isHttpOrHttps and
    // isValidUri passed, and only the later HttpRequest.newBuilder().uri(...) inside
    // sendDetailPageRequest rejected it (with IllegalArgumentException: unsupported URI),
    // uncaught there and propagating out to execute()'s outer catch - ending the entire run
    // instead of skipping just this one entry.
    serve(
        "/feed.xml",
        200,
        "application/rss+xml",
        feedXml("http://ex_ample.invalid/a.html", baseUrl + "/ok.html"));
    serve("/ok.html", 200, "text/html", "<html><body><main>Text</main></body></html>");
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(1), eq(1));
    verify(fileProcessingService, timeout(2000))
        .processRssEntry(anyString(), anyString(), eq(baseUrl + "/ok.html"), any(), eq(library));
    verify(indexingRunEventRepository, timeout(2000))
        .save(
            argThat(
                event ->
                    event.getCategory() == IndexingEventCategory.REJECTED
                        && "http://ex_ample.invalid/a.html".equals(event.getReference())));
  }

  @Test
  void aDetailPageRedirectToAnUnresolvableLocationIsSkippedAndTheRunContinues() {
    // PR #664 review, finding 1b: a redirect hop's own Location header is server-controlled input
    // no pre-validation of entryUrl can cover - sendDetailPageRequest's
    // currentUri.resolve(location) can itself throw IllegalArgumentException for a Location value
    // that is not a valid relative/absolute reference at all (here: a space inside the authority).
    // Before this fix, that exception propagated out of processEntry entirely, ending the run
    // instead of skipping just this one entry.
    serve(
        "/feed.xml",
        200,
        "application/rss+xml",
        feedXml(baseUrl + "/a.html", baseUrl + "/ok.html"));
    server.createContext(
        "/a.html",
        exchange -> {
          exchange.getResponseHeaders().set("Location", "http://ex ample.invalid/x");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    serve("/ok.html", 200, "text/html", "<html><body><main>Text</main></body></html>");
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(1), eq(1));
    verify(fileProcessingService, timeout(2000))
        .processRssEntry(anyString(), anyString(), eq(baseUrl + "/ok.html"), any(), eq(library));
    verify(indexingRunEventRepository, timeout(2000))
        .save(
            argThat(
                event ->
                    event.getCategory() == IndexingEventCategory.REJECTED
                        && (baseUrl + "/a.html").equals(event.getReference())));
  }

  @Test
  void aDetailPageRedirectedToAHostUriCannotParseIsRejectedAndTheRunContinues() {
    // PR #664 review, finding 3: the RSS variant of isForeignHostRedirect had no dedicated test -
    // a detail-page redirect whose target host URI cannot parse (here: an underscore) must be
    // rejected as foreign (RejectedByRemoteException -> REJECTED event, entry skipped), not crash
    // the run and not be treated as same-origin.
    serve(
        "/feed.xml",
        200,
        "application/rss+xml",
        feedXml(baseUrl + "/a.html", baseUrl + "/ok.html"));
    server.createContext(
        "/a.html",
        exchange -> {
          exchange.getResponseHeaders().set("Location", "http://ex_ample.invalid/x");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    serve("/ok.html", 200, "text/html", "<html><body><main>Text</main></body></html>");
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(1), eq(1));
    verify(fileProcessingService, timeout(2000))
        .processRssEntry(anyString(), anyString(), eq(baseUrl + "/ok.html"), any(), eq(library));
    verify(indexingRunEventRepository, timeout(2000))
        .save(
            argThat(
                event ->
                    event.getCategory() == IndexingEventCategory.REJECTED
                        && (baseUrl + "/a.html").equals(event.getReference())
                        // Maintainer nachtrag to #693 (21.08.2026): a rejected redirect gets its
                        // own, distinguishable message ("...auf einen fremden Host abgelehnt"),
                        // not the generic HTTP-status wording ("abgewiesen") #513 introduced for
                        // a 403/429 response.
                        && event.getMessage() != null
                        && event.getMessage().contains("fremden Host")));
  }

  @Test
  void aRejectedRedirectsMessageNeverCarriesTheFullTargetUrlOnlyItsSanitizedOrigin() {
    // Maintainer nachtrag to #693 (21.08.2026): the redirect's own Location header is
    // server-controlled input that can carry a token or other sensitive query parameter - the
    // run-log message must name only the rejected target's scheme and host, never its path or
    // query. The pre-existing #513 comment on isForeignHostRedirect ("must never reach the UI")
    // is about the *unsanitized* raw target this test's foreign server URL below stands in for.
    serve(
        "/feed.xml",
        200,
        "application/rss+xml",
        feedXml(baseUrl + "/a.html", baseUrl + "/ok.html"));
    server.createContext(
        "/a.html",
        exchange -> {
          exchange
              .getResponseHeaders()
              .set("Location", "https://angreifer.invalid/pfad?token=geheimwert");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    serve("/ok.html", 200, "text/html", "<html><body><main>Text</main></body></html>");
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(1), eq(1));
    verify(indexingRunEventRepository, timeout(2000))
        .save(
            argThat(
                event ->
                    event.getCategory() == IndexingEventCategory.REJECTED
                        && (baseUrl + "/a.html").equals(event.getReference())
                        && event.getMessage() != null
                        && event.getMessage().contains("https://angreifer.invalid")
                        && !event.getMessage().contains("token")
                        && !event.getMessage().contains("geheimwert")
                        && !event.getMessage().contains("pfad")));
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

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(0), eq(0), eq(1), eq(0));
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

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(0), eq(0), eq(1), eq(0));
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

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(0), eq(1));
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

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(0), eq(1));
    verify(feedStateRepository, timeout(2000))
        .save(argThat(state -> "\"etag-success\"".equals(state.getEtag())));
  }

  @Test
  void aTargetLibraryDeletedDuringTheRunSurfacesAsAGermanRunFailureNotARawJdbcMessage() {
    // #646, PR #665 review, optional finding 6: fk_rss_feed_state_library (migration 045) turns
    // the delete-during-run race into a DataIntegrityViolationException the moment saveFeedState
    // tries to write - simulated here by making the repository throw exactly that, since actually
    // racing KnowledgeLibraryService#deleteLibrary against this executor would need a second,
    // real Spring context.
    serveFeedWithEtag("/feed.xml", feedXml(baseUrl + "/a.html"), "\"etag-race\"");
    serve("/a.html", 200, "text/html", "<html><body><main>Text</main></body></html>");
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);
    when(feedStateRepository.save(any()))
        .thenThrow(
            new org.springframework.dao.DataIntegrityViolationException(
                "insert or update on table \"rss_feed_state\" violates foreign key constraint"
                    + " \"fk_rss_feed_state_library\""));

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000))
        .failJob(any(), eq("Die Bibliothek wurde während des Laufs gelöscht."));
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
    // #518: documentsIndexedTotal counts the entry's own document plus its attachment (2), while
    // documentsProcessed still counts only the one feed entry.
    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(0), eq(2));
  }

  @Test
  void genericProfileAcceptsAPdfLinkedUnderAnUnrecognizedExtensionAndReportsTheMismatch()
      throws IOException {
    // #404 review, finding 2: the core case #404 exists for, now proven on the RSS attachment
    // path too - a document linked under an extension SupportedDocumentFormats does not recognize
    // ("bescheid.csv") used to never even become a candidate, and is now accepted from its actual
    // content, with the deviation reported instead of hidden.
    executor =
        newExecutor(
            new IndexingProperties.Rss(
                200, 10_000, 10_000, 0, null, null, AttachmentProfile.GENERIC, 10, 10_000));
    String detailHtml =
        "<html><body><main>Text"
            + "<a href=\""
            + baseUrl
            + "/downloads/bescheid.csv\">Bescheid</a></main></body></html>";
    serve("/feed.xml", 200, "application/rss+xml", feedXml(baseUrl + "/a.html"));
    serve("/a.html", 200, "text/html", detailHtml);
    serveBytes(
        "/downloads/bescheid.csv",
        200,
        "text/csv",
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
            eq("bescheid.csv"),
            eq(baseUrl + "/downloads/bescheid.csv"),
            any(),
            anyLong(),
            eq(library),
            eq(DocumentSourceType.RSS_FEED),
            eq(baseUrl + "/a.html"));
    verify(indexingRunEventRepository, timeout(2000))
        .save(
            argThat(
                event ->
                    event.getCategory() == IndexingEventCategory.FORMAT_MISMATCH
                        && event.getReference().equals(baseUrl + "/downloads/bescheid.csv")));
  }

  @Test
  void anAttachmentOverTheLibraryStorageQuotaIsSkippedRecordedAsRejectedAndDefersTheFeedEtag()
      throws IOException {
    // #119, PR #700 review finding 4: the attachment branch is the one QUOTA_EXCEEDED handler
    // that behaves differently from the other three (AsyncIndexingExecutor, UrlIndexingExecutor,
    // RssFeedIndexingExecutor's own entry-level handling just above) - it never calls
    // progress.recordSkipped() (an attachment was never counted as a discrete unit of the run's
    // total to begin with, see #518) and instead sets anyEntryDeferred, the same deliberate "try
    // this attachment again next run" behaviour #492's lost-attachment handling already uses.
    executor =
        newExecutor(
            new IndexingProperties.Rss(
                200, 10_000, 10_000, 0, null, null, AttachmentProfile.GENERIC, 10, 10_000));
    String detailHtml =
        "<html><body><main>Text"
            + "<a href=\""
            + baseUrl
            + "/downloads/over-quota.pdf\">Anlage</a></main></body></html>";
    serveFeedWithEtag("/feed.xml", feedXml(baseUrl + "/a.html"), "\"etag-over-quota-attachment\"");
    serve("/a.html", 200, "text/html", detailHtml);
    serveBytes(
        "/downloads/over-quota.pdf",
        200,
        "application/pdf",
        "%PDF-1.4 not real content".getBytes(StandardCharsets.UTF_8));
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);
    when(fileProcessingService.processUrlFile(
            any(), anyString(), anyString(), any(), anyLong(), eq(library), any(), anyString()))
        .thenReturn(FileProcessingResult.QUOTA_EXCEEDED);
    when(storageQuotaService.quotaExceededMessage(library.getId()))
        .thenReturn("Speicherkontingent der Bibliothek erschöpft (10,0 GB von 10,0 GB belegt)");

    execute(baseUrl + "/feed.xml");

    String expectedMessage =
        "Speicherkontingent der Bibliothek erschöpft (10,0 GB von 10,0 GB belegt)";
    // The entry itself still counts as processed and its own document as indexed - only the
    // attachment was rejected, so documentsIndexedTotal stays at 1 (the entry), not 2.
    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(0), eq(1));
    verify(indexingRunEventRepository, timeout(2000))
        .save(
            argThat(
                event ->
                    event.getCategory() == IndexingEventCategory.REJECTED
                        && (baseUrl + "/downloads/over-quota.pdf").equals(event.getReference())
                        && expectedMessage.equals(event.getMessage())));
    // Deferred, exactly like a lost/oversized attachment - a future 304 on the feed must not
    // permanently suppress retrying this attachment.
    verify(feedStateRepository, never()).save(any());
  }

  @Test
  void anEntryWithMultipleAttachmentsIncreasesTheDocumentCounterForEachAttachment()
      throws IOException {
    // #518 acceptance criteria: a feed entry carrying several attachments must increase the
    // document counter (documentsIndexedTotal) by one for every attachment indexed, not just for
    // the entry itself - documentsProcessed stays at one feed entry regardless of how many
    // attachments it carries.
    executor =
        newExecutor(
            new IndexingProperties.Rss(
                200, 10_000, 10_000, 0, null, null, AttachmentProfile.GENERIC, 10, 10_000));
    String detailHtml =
        "<html><body><main>Text"
            + "<a href=\""
            + baseUrl
            + "/downloads/erste.pdf\">Erste</a>"
            + "<a href=\""
            + baseUrl
            + "/downloads/zweite.pdf\">Zweite</a>"
            + "<a href=\""
            + baseUrl
            + "/downloads/dritte.pdf\">Dritte</a></main></body></html>";
    serve("/feed.xml", 200, "application/rss+xml", feedXml(baseUrl + "/a.html"));
    serve("/a.html", 200, "text/html", detailHtml);
    serveBytes(
        "/downloads/erste.pdf",
        200,
        "application/pdf",
        "%PDF-1.4 erste".getBytes(StandardCharsets.UTF_8));
    serveBytes(
        "/downloads/zweite.pdf",
        200,
        "application/pdf",
        "%PDF-1.4 zweite".getBytes(StandardCharsets.UTF_8));
    serveBytes(
        "/downloads/dritte.pdf",
        200,
        "application/pdf",
        "%PDF-1.4 dritte".getBytes(StandardCharsets.UTF_8));
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);
    when(fileProcessingService.processUrlFile(
            any(), anyString(), anyString(), any(), anyLong(), eq(library), any(), anyString()))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    // One feed entry processed, but four documents indexed in total: the entry's own document
    // plus its three attachments.
    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(0), eq(4));
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

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(0), eq(1));
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
    serveFeedWithEtag("/feed.xml", feedXml(baseUrl + "/a.html"), "\"etag-lost-attachment\"");
    serve("/a.html", 200, "text/html", detailHtml);
    serve("/downloads/fehlt.pdf", 404, "text/html", "not found");
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    // The entry itself still counts as processed - only the attachment failed.
    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(0), eq(1));
    verify(fileProcessingService, never())
        .processUrlFile(any(), any(), any(), any(), anyLong(), any(), any(), any());
    // #492 review, finding 2: a lost attachment must defer the feed's ETag persistence the same
    // way a lost entry does - otherwise a future 304 would permanently suppress a retry.
    verify(feedStateRepository, never()).save(any());
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
    serveFeedWithEtag("/feed.xml", feedXml(baseUrl + "/a.html"), "\"etag-oversize-attachment\"");
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

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(0), eq(1));
    verify(fileProcessingService, never())
        .processUrlFile(any(), any(), any(), any(), anyLong(), any(), any(), any());
    verify(feedStateRepository, never()).save(any());
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
        "/downloads/erste.pdf",
        200,
        "application/pdf",
        "%PDF-1.4 erste".getBytes(StandardCharsets.UTF_8));
    serveBytes(
        "/downloads/zweite.pdf",
        200,
        "application/pdf",
        "%PDF-1.4 zweite".getBytes(StandardCharsets.UTF_8));
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
    verify(feedStateRepository, never()).save(any());
  }

  @Test
  void anAttachmentAnsweringWithHtmlInsteadOfTheExpectedFormatIsSkipped() throws IOException {
    // #492 review, finding 3: a bot-protection challenge or 200-status error page served for a
    // link a profile identified via its .pdf extension must never be trusted just because the URL
    // carried a supported extension.
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
    serve(
        "/downloads/anlage.pdf", 200, "text/html", "<html><body>Zugriff verweigert</body></html>");
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(0), eq(1));
    verify(fileProcessingService, never())
        .processUrlFile(any(), any(), any(), any(), anyLong(), any(), any(), any());
  }

  @Test
  void detailPageFollowsASameOriginRedirect() {
    // #538 follow-up review, finding 4: sendDetailPageRequest's own manual redirect loop needs a
    // same-origin positive test - buildHttpClient no longer auto-follows this at the JDK level
    // (Redirect.NEVER), so a legitimate redirect (e.g. a trailing-slash or path normalization) must
    // still be chased by sendDetailPageRequest itself.
    String detailHtml =
        "<html><body><main><article>Der eigentliche Artikeltext.</article></main></body></html>";
    serve("/feed.xml", 200, "application/rss+xml", feedXml(baseUrl + "/a.html"));
    server.createContext(
        "/a.html",
        exchange -> {
          exchange.getResponseHeaders().set("Location", baseUrl + "/a-final.html");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    serve("/a-final.html", 200, "text/html", detailHtml);
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
    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(0), eq(1));
  }

  @Test
  void anAttachmentRedirectedToAForeignHostIsSkipped() throws IOException {
    // #492 review, finding 4: a same-host link a profile already vetted must not silently end up
    // downloading from, and being recorded as originating from, an address the profile never
    // approved - mirrors fetchDetailPage's own isForeignHostRedirect check.
    // 127.0.0.2, not 127.0.0.1 - see BoundedDownloaderTest's identical comment.
    HttpServer foreignServer = HttpServer.create(new InetSocketAddress("127.0.0.2", 0), 0);
    foreignServer.start();
    String foreignBaseUrl = "http://127.0.0.2:" + foreignServer.getAddress().getPort();
    try {
      foreignServer.createContext(
          "/anlage.pdf",
          exchange -> {
            byte[] bytes = "fremd".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/pdf");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });

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
      server.createContext(
          "/downloads/anlage.pdf",
          exchange -> {
            exchange.getResponseHeaders().set("Location", foreignBaseUrl + "/anlage.pdf");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
          });
      when(fileProcessingService.processRssEntry(
              anyString(), anyString(), anyString(), any(), eq(library)))
          .thenReturn(FileProcessingResult.PROCESSED);

      execute(baseUrl + "/feed.xml");

      verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(0), eq(1));
      verify(fileProcessingService, never())
          .processUrlFile(any(), any(), any(), any(), anyLong(), any(), any(), any());
    } finally {
      foreignServer.stop(0);
    }
  }

  @Test
  void attachmentDownloadSendsTheConfiguredUserAgent() throws IOException {
    // #492 review, finding 6: the feed and every detail page already send the configured
    // User-Agent - an attachment request left it out entirely.
    executor =
        newExecutor(
            new IndexingProperties.Rss(
                200,
                10_000,
                10_000,
                0,
                "OPAA-Indexer/attachment-test",
                null,
                AttachmentProfile.GENERIC,
                10,
                10_000));
    String detailHtml =
        "<html><body><main>Text"
            + "<a href=\""
            + baseUrl
            + "/downloads/anlage.pdf\">Anlage</a></main></body></html>";
    serve("/feed.xml", 200, "application/rss+xml", feedXml(baseUrl + "/a.html"));
    serve("/a.html", 200, "text/html", detailHtml);
    AtomicReference<String> userAgent = new AtomicReference<>();
    server.createContext(
        "/downloads/anlage.pdf",
        exchange -> {
          userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
          byte[] bytes = "%PDF-1.4 not real content".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/pdf");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);
    when(fileProcessingService.processUrlFile(
            any(), anyString(), anyString(), any(), anyLong(), eq(library), any(), anyString()))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml");

    verify(fileProcessingService, timeout(2000))
        .processUrlFile(
            any(), anyString(), anyString(), any(), anyLong(), eq(library), any(), anyString());
    assertThat(userAgent.get()).isEqualTo("OPAA-Indexer/attachment-test");
  }

  // --- #505: sourceCredentials/sourceProxy applied to the feed fetch, detail pages and
  // attachments, mirroring UrlIndexingExecutor ---

  private static String expectedBasicAuth(String credentials) {
    return "Basic "
        + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void feedRequestSendsTheConfiguredAuthorizationHeader() {
    AtomicReference<String> authorization = new AtomicReference<>();
    server.createContext(
        "/feed.xml",
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] bytes = feedXml().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/rss+xml");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });

    execute(baseUrl + "/feed.xml", null, "user:pass");

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(0), eq(0), eq(0), eq(0));
    assertThat(authorization.get()).isEqualTo(expectedBasicAuth("user:pass"));
  }

  @Test
  void detailPageRequestSendsTheConfiguredAuthorizationHeader() {
    serve("/feed.xml", 200, "application/rss+xml", feedXml(baseUrl + "/a.html"));
    AtomicReference<String> authorization = new AtomicReference<>();
    server.createContext(
        "/a.html",
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] bytes =
              "<html><body><main>Text</main></body></html>".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/html");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml", null, "admin:secret");

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(0), eq(1));
    assertThat(authorization.get()).isEqualTo(expectedBasicAuth("admin:secret"));
  }

  @Test
  void attachmentDownloadSendsTheConfiguredAuthorizationHeader() throws IOException {
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
    AtomicReference<String> authorization = new AtomicReference<>();
    server.createContext(
        "/downloads/anlage.pdf",
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] bytes = "%PDF-1.4 not real content".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/pdf");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    when(fileProcessingService.processRssEntry(
            anyString(), anyString(), anyString(), any(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);
    when(fileProcessingService.processUrlFile(
            any(), anyString(), anyString(), any(), anyLong(), eq(library), any(), anyString()))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute(baseUrl + "/feed.xml", null, "attachment:credentials");

    verify(fileProcessingService, timeout(2000))
        .processUrlFile(
            any(), anyString(), anyString(), any(), anyLong(), eq(library), any(), anyString());
    assertThat(authorization.get()).isEqualTo(expectedBasicAuth("attachment:credentials"));
  }

  @Test
  void feedRequestUsesTheConfiguredProxyConnectionRefusedFailsTheJob() throws IOException {
    // The feed server is reachable directly, but every request must still be routed through the
    // configured (here: closed, unreachable) proxy instead - proving sourceProxy is actually
    // applied to SourceHttpClientFactory.buildHttpClient, mirroring UrlIndexingExecutor.
    serve("/feed.xml", 200, "application/rss+xml", feedXml(baseUrl + "/a.html"));
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }

    execute(baseUrl + "/feed.xml", "127.0.0.1:" + closedPort, null);

    // The underlying connect-refused exception's own message can be null depending on the JDK's
    // networking stack - unlike the other failJob assertions in this class, this test only cares
    // that the run actually failed, not what it says.
    verify(indexingJobService, timeout(2000)).failJob(any(), any());
  }

  // --- PR #642 review, finding 1: the feed's own origin, not just the redirect chain ---

  @Test
  void aFeedEntryOnAForeignHostNeverReceivesTheAuthorizationHeader() throws IOException {
    // Unlike every foreign-host-redirect test above, nothing here goes through a redirect at all -
    // the feed's own <link> names a foreign address directly (127.0.0.2, not 127.0.0.1 - see
    // BoundedDownloaderTest's identical comment), the way a malicious or careless feed operator
    // could write <link>https://angreifer.example/x</link>. Credentials configured for the feed's
    // own host (baseUrl) must never reach it - the entry itself is still processed normally, only
    // the header is withheld, so an aggregator feed without its own credentials keeps working.
    HttpServer foreignServer = HttpServer.create(new InetSocketAddress("127.0.0.2", 0), 0);
    foreignServer.start();
    String foreignBaseUrl = "http://127.0.0.2:" + foreignServer.getAddress().getPort();
    AtomicReference<String> authorization = new AtomicReference<>("(never contacted)");
    try {
      foreignServer.createContext(
          "/a.html",
          exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes =
                "<html><body><main>Text</main></body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      serve("/feed.xml", 200, "application/rss+xml", feedXml(foreignBaseUrl + "/a.html"));
      when(fileProcessingService.processRssEntry(
              anyString(), anyString(), anyString(), any(), eq(library)))
          .thenReturn(FileProcessingResult.PROCESSED);

      execute(baseUrl + "/feed.xml", null, "admin:secret");

      verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(0), eq(1));
      assertThat(authorization.get()).isNull();
    } finally {
      foreignServer.stop(0);
    }
  }

  @Test
  void anAttachmentOnAForeignHostNeverReceivesTheAuthorizationHeader() throws IOException {
    // The entry's own detail page and its attachment both live on the feed's foreign host here -
    // AttachmentProfile.GENERIC only ever proposes candidates same-origin to the detail page (see
    // aLinkToAForeignHostIsNeverTreatedAsAnAttachment above), so an attachment foreign to the
    // *feed*
    // but same-origin to a foreign *detail page* is exactly the gap PR #642 review finding 1
    // describes one level down from the entry itself.
    executor =
        newExecutor(
            new IndexingProperties.Rss(
                200, 10_000, 10_000, 0, null, null, AttachmentProfile.GENERIC, 10, 10_000));
    HttpServer foreignServer = HttpServer.create(new InetSocketAddress("127.0.0.2", 0), 0);
    foreignServer.start();
    String foreignBaseUrl = "http://127.0.0.2:" + foreignServer.getAddress().getPort();
    AtomicReference<String> authorization = new AtomicReference<>("(never contacted)");
    try {
      String detailHtml =
          "<html><body><main>Text"
              + "<a href=\""
              + foreignBaseUrl
              + "/downloads/anlage.pdf\">Anlage</a></main></body></html>";
      foreignServer.createContext(
          "/a.html",
          exchange -> {
            byte[] bytes = detailHtml.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      foreignServer.createContext(
          "/downloads/anlage.pdf",
          exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = "%PDF-1.4 not real content".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/pdf");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      serve("/feed.xml", 200, "application/rss+xml", feedXml(foreignBaseUrl + "/a.html"));
      when(fileProcessingService.processRssEntry(
              anyString(), anyString(), anyString(), any(), eq(library)))
          .thenReturn(FileProcessingResult.PROCESSED);
      when(fileProcessingService.processUrlFile(
              any(), anyString(), anyString(), any(), anyLong(), eq(library), any(), anyString()))
          .thenReturn(FileProcessingResult.PROCESSED);

      execute(baseUrl + "/feed.xml", null, "attachment:credentials");

      verify(fileProcessingService, timeout(2000))
          .processUrlFile(
              any(), anyString(), anyString(), any(), anyLong(), eq(library), any(), anyString());
      assertThat(authorization.get()).isNull();
    } finally {
      foreignServer.stop(0);
    }
  }

  // --- PR #642 review, finding 2: a cross-origin redirect never leaks a *configured* header ---

  @Test
  void anAttachmentRedirectedToAForeignHostWithCredentialsConfiguredNeverLeaksTheHeader()
      throws IOException {
    // Mirrors anAttachmentRedirectedToAForeignHostIsSkipped above, but with sourceCredentials
    // actually configured this time (every foreign-host-redirect test until now ran with no
    // authHeader at all, so none of them could have caught a leak here even if one existed). The
    // redirect to the foreign host is rejected outright before it is ever followed (ADR-0017), so
    // the foreign server is never contacted at all - the strongest possible proof that no header
    // (or anything else) reaches it.
    HttpServer foreignServer = HttpServer.create(new InetSocketAddress("127.0.0.2", 0), 0);
    foreignServer.start();
    String foreignBaseUrl = "http://127.0.0.2:" + foreignServer.getAddress().getPort();
    AtomicReference<String> authorization = new AtomicReference<>("(never contacted)");
    try {
      foreignServer.createContext(
          "/anlage.pdf",
          exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = "fremd".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/pdf");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });

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
      server.createContext(
          "/downloads/anlage.pdf",
          exchange -> {
            exchange.getResponseHeaders().set("Location", foreignBaseUrl + "/anlage.pdf");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
          });
      when(fileProcessingService.processRssEntry(
              anyString(), anyString(), anyString(), any(), eq(library)))
          .thenReturn(FileProcessingResult.PROCESSED);

      execute(baseUrl + "/feed.xml", null, "admin:secret");

      verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(0), eq(1));
      verify(fileProcessingService, never())
          .processUrlFile(any(), any(), any(), any(), anyLong(), any(), any(), any());
      assertThat(authorization.get()).isEqualTo("(never contacted)");
    } finally {
      foreignServer.stop(0);
    }
  }

  @Test
  void anInvalidSourceProxyPortFailsTheJobWithAGermanMessage() {
    // PR #642 review, finding 4: reusing the shared ProxyAndCredentials.parse means an invalid
    // port now fails with the same German message SourceConnectionTestService already gave,
    // instead of the JDK's own NumberFormatException text leaking into progress.fail via the
    // outer catch (Exception e) this used to fall through to.
    serve("/feed.xml", 200, "application/rss+xml", feedXml(baseUrl + "/a.html"));

    execute(baseUrl + "/feed.xml", "127.0.0.1:not-a-port", null);

    verify(indexingJobService, timeout(2000))
        .failJob(any(), eq(ProxyAndCredentials.INVALID_PROXY_MESSAGE));
  }
}
