package io.opaa.indexing.source.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.IndexingEventCategory;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.IndexingRunEvent;
import io.opaa.indexing.IndexingRunEventRepository;
import io.opaa.indexing.StaleDocumentCleanupService;
import io.opaa.indexing.SupportedDocumentFormats;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.ProxyAndCredentials;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link UrlIndexingExecutor#execute} end to end against a local {@code
 * com.sun.net.httpserver.HttpServer} stub, mirroring {@code RssFeedIndexingExecutorTest}'s pattern
 * - {@link AutoindexCrawlerService} and {@link BoundedDownloader} are the real implementations
 * here, only {@link FileProcessingService}/{@link IndexingJobService}/{@link DocumentRepository}/
 * {@link IndexingRunEventRepository} are mocked.
 *
 * <p>Covers #404 review, finding 1 (the BLOCKER): a crawled entry this run ends up rejecting must
 * never reach {@link FileProcessingService#processUrlFile} and must count as skipped, not failed -
 * {@code BoundedDownloaderTest} already proves the underlying {@code downloadPrefix} call itself
 * never reads more than its cap; this class proves the executor actually uses that bounded read to
 * decide before ever calling the unbounded {@code download}.
 */
class UrlIndexingExecutorExecuteTest {

  private HttpServer server;
  private String baseUrl;

  private FileProcessingService fileProcessingService;
  private IndexingJobService indexingJobService;
  private DocumentRepository documentRepository;
  private IndexingRunEventRepository indexingRunEventRepository;
  private StaleDocumentCleanupService staleDocumentCleanupService;
  private UrlIndexingExecutor executor;

  private final KnowledgeLibrary library =
      KnowledgeLibrary.ownedByUser(
          UUID.randomUUID(),
          "Webverzeichnis",
          null,
          UUID.randomUUID(),
          LibraryVisibility.PRIVATE,
          false,
          DocumentSourceType.HTTP_DIRECTORY,
          null,
          null,
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
    when(documentRepository.findByLibraryIdAndFilePath(any(), anyString()))
        .thenReturn(Optional.empty());
    indexingRunEventRepository = mock(IndexingRunEventRepository.class);
    staleDocumentCleanupService = mock(StaleDocumentCleanupService.class);

    // Target validation is exercised on its own dedicated stand (TargetAddressValidatorTest) -
    // disabled here so a loopback test server is actually reachable, mirroring
    // BoundedDownloaderTest/RssFeedIndexingExecutorTest's own setup.
    targetAddressValidator = TargetAddressValidator.disabled();
    executor = buildExecutor(new CrawlProperties(0, 0, 0));
  }

  private TargetAddressValidator targetAddressValidator;
  private BoundedDownloader downloader;

  /** Every temp file {@link BoundedDownloader#download} handed out during the run, in order. */
  private final List<Path> fullDownloads = new CopyOnWriteArrayList<>();

  /** How often each served path was actually requested - one entry per {@link #serve} context. */
  private final Map<String, AtomicInteger> requestCounts = new ConcurrentHashMap<>();

  /**
   * Builds an executor sharing every mocked collaborator, only {@code crawlProperties} varying -
   * {@link #aTruncatedCrawlNeverCallsStaleDocumentCleanup} needs a low {@code maxEntries} to force
   * {@link AutoindexCrawlerService.CrawlResult#truncated()}; every other test keeps {@link
   * #setUp}'s generous default.
   */
  private UrlIndexingExecutor buildExecutor(CrawlProperties crawlProperties) {
    // A spy over the real downloader, not a mock: every test still performs genuine transfers, but
    // the temp files handed back can be checked for deletion after the run.
    downloader = spy(new BoundedDownloader(targetAddressValidator));
    try {
      doAnswer(
              invocation -> {
                Path downloaded = (Path) invocation.callRealMethod();
                fullDownloads.add(downloaded);
                return downloaded;
              })
          .when(downloader)
          .download(any(), any(), anyString(), anyString(), anyLong());
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException(e);
    }
    return new UrlIndexingExecutor(
        new AutoindexCrawlerService(targetAddressValidator, crawlProperties),
        downloader,
        fileProcessingService,
        indexingJobService,
        documentRepository,
        indexingRunEventRepository,
        mock(LibraryStorageQuotaService.class),
        staleDocumentCleanupService,
        crawlProperties,
        mock(io.opaa.library.LibraryFolderService.class));
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  private void serve(String path, String contentType, byte[] body) {
    AtomicInteger requests = requestCounts.computeIfAbsent(path, ignored -> new AtomicInteger());
    server.createContext(
        path,
        exchange -> {
          requests.incrementAndGet();
          exchange.getResponseHeaders().set("Content-Type", contentType);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
  }

  private void execute() {
    library.updateSourceConfiguration(null, baseUrl + "/files/", null, null, false);
    UUID jobId = UUID.randomUUID();
    executor.execute(jobId, library, IndexingRunMode.FULL);
    verify(indexingJobService, timeout(5000))
        .completeJob(eq(jobId), anyInt(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void acceptsAMislabeledPdfAndReportsTheMismatchInsteadOfRejectingItByExtension()
      throws IOException {
    // The core case #404 exists for, now proven on a real request/response round trip rather than
    // only through UrlIndexingExecutor#decideForEntry in isolation.
    serve(
        "/files/",
        "text/html",
        ("<html><head><title>Index of /files/</title></head><body><ul>"
                + "<li><a href=\"bescheid.csv\">bescheid.csv</a></li>"
                + "</ul></body></html>")
            .getBytes(StandardCharsets.UTF_8));
    serve(
        "/files/bescheid.csv",
        "text/csv",
        "%PDF-1.4\n%mock-pdf-body-for-magic-byte-detection".getBytes(StandardCharsets.UTF_8));
    when(fileProcessingService.processUrlFile(
            any(),
            anyString(),
            anyString(),
            any(),
            anyLong(),
            eq(library),
            eq(DocumentSourceType.HTTP_DIRECTORY),
            isNull(),
            isNull(),
            any()))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute();

    verify(fileProcessingService, timeout(5000))
        .processUrlFile(
            any(),
            eq("bescheid.csv"),
            anyString(),
            any(),
            anyLong(),
            eq(library),
            eq(DocumentSourceType.HTTP_DIRECTORY),
            isNull(),
            isNull(),
            any());
    verify(indexingRunEventRepository, timeout(5000))
        .save(argThat(categoryIs(IndexingEventCategory.FORMAT_MISMATCH)));
  }

  @Test
  void rejectsUnsupportedContentWithoutEverCallingFileProcessingServiceAndCountsItSkippedNotFailed()
      throws IOException {
    // #404 review, finding 1 (BLOCKER): before the fix, this entry would have been fully
    // downloaded (unbounded) before being rejected; a network hiccup mid-transfer would then have
    // counted it as ERROR instead of the clean "skipped" a name-based pre-filter used to produce.
    serve(
        "/files/",
        "text/html",
        ("<html><head><title>Index of /files/</title></head><body><ul>"
                + "<li><a href=\"grosse-datei.iso\">grosse-datei.iso</a></li>"
                + "</ul></body></html>")
            .getBytes(StandardCharsets.UTF_8));
    serve(
        "/files/grosse-datei.iso",
        "application/octet-stream",
        // Binary garbage Tika cannot resolve to any accepted type.
        "x".repeat(10_000).getBytes(StandardCharsets.UTF_8));

    execute();

    verify(fileProcessingService, never())
        .processUrlFile(any(), any(), any(), any(), anyLong(), any(), any(), any(), any(), any());
    verify(indexingJobService, timeout(5000)).completeJob(any(), eq(0), eq(0), eq(1), eq(0));
    verify(indexingRunEventRepository, timeout(5000))
        .save(argThat(categoryIs(IndexingEventCategory.UNSUPPORTED_FORMAT)));
  }

  @Test
  void acceptsAnOutlookMsgLargerThanTheDetectionPrefixServedAsOctetStream() throws IOException {
    // Regression guard for #1229: an OLE2 file's directory sector may sit past the bounded
    // detection prefix, so a genuine .msg larger than SupportedDocumentFormats
    // #DETECTION_PREFIX_BYTES detects only as the generic application/x-tika-msoffice there. That
    // generic type is deliberately not an accepted .msg content, so the entry used to be rejected
    // as an unsupported format - while the very same file is indexed fine via upload/FILESYSTEM,
    // which always detect on the complete file. The server's own Content-Type never enters the
    // decision; application/octet-stream (Apache's default for .msg) is served here to prove it.
    byte[] msg = resourceBytes("test-documents/mail/attachment_msg_pdf.msg");
    assertThat(msg.length)
        .as("the fixture must be larger than the detection prefix for this to reproduce at all")
        .isGreaterThan(SupportedDocumentFormats.DETECTION_PREFIX_BYTES);
    serve(
        "/files/",
        "text/html",
        ("<html><head><title>Index of /files/</title></head><body><ul>"
                + "<li><a href=\"outlook-mail-mit-pdf-anhang.msg\">"
                + "outlook-mail-mit-pdf-anhang.msg</a></li>"
                + "</ul></body></html>")
            .getBytes(StandardCharsets.UTF_8));
    serve("/files/outlook-mail-mit-pdf-anhang.msg", "application/octet-stream", msg);
    when(fileProcessingService.processUrlFile(
            any(),
            anyString(),
            anyString(),
            any(),
            anyLong(),
            eq(library),
            eq(DocumentSourceType.HTTP_DIRECTORY),
            isNull(),
            isNull(),
            any()))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute();

    verify(fileProcessingService, timeout(5000))
        .processUrlFile(
            any(),
            eq("outlook-mail-mit-pdf-anhang.msg"),
            anyString(),
            any(),
            anyLong(),
            eq(library),
            eq(DocumentSourceType.HTTP_DIRECTORY),
            isNull(),
            isNull(),
            any());
    verify(indexingRunEventRepository, never())
        .save(argThat(categoryIs(IndexingEventCategory.UNSUPPORTED_FORMAT)));
    verify(indexingRunEventRepository, never())
        .save(argThat(categoryIs(IndexingEventCategory.FORMAT_MISMATCH)));
    assertThat(requestCounts.get("/files/outlook-mail-mit-pdf-anhang.msg"))
        .as(
            "the file the fallback had to fetch is reused for processing, so the entry costs one"
                + " bounded read plus exactly one full transfer - never a second one")
        .hasValue(2);
    assertThat(fullDownloads).hasSize(1);
  }

  @Test
  void deletesTheFallbackDownloadAndReportsOneRejectionWhenTheCompleteFileIsUnsupported()
      throws IOException {
    // The other half of #1229's new lifecycle: an OLE2 container that is neither .msg nor .doc
    // (an .xls, .ppt or any other legacy Office file next to the bestand) also detects as the
    // generic application/x-tika-msoffice, so it takes the same fallback download - and is then
    // rejected on its complete bytes. The temp file must not survive that, and the entry must
    // produce exactly one UNSUPPORTED_FORMAT event, not one per decision step.
    byte[] ole2Header = {
      (byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1
    };
    byte[] body = new byte[SupportedDocumentFormats.DETECTION_PREFIX_BYTES + 4_096];
    System.arraycopy(ole2Header, 0, body, 0, ole2Header.length);
    serve(
        "/files/",
        "text/html",
        ("<html><head><title>Index of /files/</title></head><body><ul>"
                + "<li><a href=\"haushalt.xls\">haushalt.xls</a></li>"
                + "</ul></body></html>")
            .getBytes(StandardCharsets.UTF_8));
    serve("/files/haushalt.xls", "application/octet-stream", body);

    execute();

    verify(fileProcessingService, never())
        .processUrlFile(any(), any(), any(), any(), anyLong(), any(), any(), any(), any(), any());
    verify(indexingRunEventRepository, timeout(5000).times(1))
        .save(argThat(categoryIs(IndexingEventCategory.UNSUPPORTED_FORMAT)));
    verify(indexingJobService, timeout(5000)).completeJob(any(), eq(0), eq(0), eq(1), eq(0));
    assertThat(fullDownloads)
        .as("the fallback must have downloaded the entry in full to reach its verdict")
        .hasSize(1);
    assertThat(fullDownloads.getFirst())
        .as("a rejected fallback download must not survive the run")
        .doesNotExist();
  }

  @Test
  void anEntryAboveTheSizeCapIsRejectedAsSkippedWhileTheRestOfTheRunContinues() throws IOException {
    // #1236: the entry's transfer is cut off at CrawlProperties#maxFileSizeBytes, so it never
    // reaches processUrlFile, counts as skipped rather than failed, leaves no temp file behind -
    // and the next entry of the same run is still indexed.
    executor = buildExecutor(new CrawlProperties(10, 5000, 100_000L));
    serve(
        "/files/",
        "text/html",
        ("<html><head><title>Index of /files/</title></head><body><ul>"
                + "<li><a href=\"riesig.txt\">riesig.txt</a></li>"
                + "<li><a href=\"klein.txt\">klein.txt</a></li>"
                + "</ul></body></html>")
            .getBytes(StandardCharsets.UTF_8));
    serve(
        "/files/riesig.txt",
        "text/plain",
        "Bericht. ".repeat(40_000).getBytes(StandardCharsets.UTF_8));
    serve("/files/klein.txt", "text/plain", "Kurzer Bericht.".getBytes(StandardCharsets.UTF_8));
    when(fileProcessingService.processUrlFile(
            any(),
            anyString(),
            anyString(),
            any(),
            anyLong(),
            eq(library),
            eq(DocumentSourceType.HTTP_DIRECTORY),
            isNull(),
            isNull(),
            any()))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute();

    verify(fileProcessingService, never())
        .processUrlFile(
            any(), eq("riesig.txt"), any(), any(), anyLong(), any(), any(), any(), any(), any());
    verify(fileProcessingService, timeout(5000))
        .processUrlFile(
            any(),
            eq("klein.txt"),
            anyString(),
            any(),
            anyLong(),
            eq(library),
            eq(DocumentSourceType.HTTP_DIRECTORY),
            isNull(),
            isNull(),
            any());
    verify(indexingRunEventRepository, timeout(5000).times(1))
        .save(
            argThat(
                event ->
                    event != null
                        && event.getCategory() == IndexingEventCategory.REJECTED
                        && event.getMessage().contains("überschreitet die zulässige Größe")));
    verify(indexingJobService, timeout(5000)).completeJob(any(), eq(1), eq(0), eq(1), eq(1));
    assertThat(fullDownloads)
        .as("only the accepted entry was ever transferred in full; the capped one never completed")
        .hasSize(1);
    assertThat(fullDownloads.getFirst())
        .as("every temp file of the run is deleted afterwards")
        .doesNotExist();
  }

  private static byte[] resourceBytes(String name) throws IOException {
    try (InputStream in =
        UrlIndexingExecutorExecuteTest.class.getClassLoader().getResourceAsStream(name)) {
      assertThat(in).as("Test resource %s must exist", name).isNotNull();
      return in.readAllBytes();
    }
  }

  @Test
  void anInvalidSourceProxyPortFailsTheJobWithAGermanMessage() throws IOException {
    // Issue #839: proxy/credentials parsing goes through the shared ProxyAndCredentials.parse
    // rather than an inline copy - an invalid port now fails with the same understandable German
    // message RssFeedIndexingExecutorTest#anInvalidSourceProxyPortFailsTheJobWithAGermanMessage
    // already proves for the RSS path (PR #642 review, finding 4), instead of the JDK's own raw
    // NumberFormatException message.
    library.updateSourceConfiguration(
        null, baseUrl + "/files/", "127.0.0.1:not-a-port", null, false);
    UUID jobId = UUID.randomUUID();

    executor.execute(jobId, library, IndexingRunMode.FULL);

    verify(indexingJobService, timeout(5000))
        .failJob(eq(jobId), eq(ProxyAndCredentials.INVALID_PROXY_MESSAGE));
    verify(fileProcessingService, never())
        .processUrlFile(any(), any(), any(), any(), anyLong(), any(), any(), any(), any(), any());
  }

  // --- #886: StaleDocumentCleanupService is only ever called after a successful, uncapped run --

  @Test
  void aSuccessfulUncappedCrawlCallsStaleDocumentCleanupWithTheCrawledUrls() throws IOException {
    serve(
        "/files/",
        "text/html",
        ("<html><head><title>Index of /files/</title></head><body><ul>"
                + "<li><a href=\"bericht.txt\">bericht.txt</a></li>"
                + "</ul></body></html>")
            .getBytes(StandardCharsets.UTF_8));
    serve("/files/bericht.txt", "text/plain", "Inhalt.".getBytes(StandardCharsets.UTF_8));
    when(fileProcessingService.processUrlFile(
            any(),
            anyString(),
            anyString(),
            any(),
            anyLong(),
            eq(library),
            eq(DocumentSourceType.HTTP_DIRECTORY),
            isNull(),
            isNull(),
            any()))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute();

    verify(staleDocumentCleanupService, timeout(5000))
        .cleanupVanished(
            eq(library),
            eq(DocumentSourceType.HTTP_DIRECTORY),
            eq(Set.of(baseUrl + "/files/bericht.txt")),
            any(),
            any(),
            any());
  }

  @Test
  void aTruncatedCrawlNeverCallsStaleDocumentCleanup() throws IOException {
    // #836/#851: a run capped by the configured entry limit must not clean up - its own
    // currentUrls would not be the source's complete bestand, so anything beyond the cut would
    // incorrectly look vanished.
    executor = buildExecutor(new CrawlProperties(10, 1, 0));
    serve(
        "/files/",
        "text/html",
        ("<html><head><title>Index of /files/</title></head><body><ul>"
                + "<li><a href=\"eins.txt\">eins.txt</a></li>"
                + "<li><a href=\"zwei.txt\">zwei.txt</a></li>"
                + "</ul></body></html>")
            .getBytes(StandardCharsets.UTF_8));
    serve("/files/eins.txt", "text/plain", "Eins.".getBytes(StandardCharsets.UTF_8));
    serve("/files/zwei.txt", "text/plain", "Zwei.".getBytes(StandardCharsets.UTF_8));
    when(fileProcessingService.processUrlFile(
            any(),
            anyString(),
            anyString(),
            any(),
            anyLong(),
            eq(library),
            eq(DocumentSourceType.HTTP_DIRECTORY),
            isNull(),
            isNull(),
            any()))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute();

    verifyNoInteractions(staleDocumentCleanupService);
  }

  @Test
  void aCrawlWithAnUnreachableSubdirectoryNeverCallsStaleDocumentCleanup() throws IOException {
    // #886 review: a subdirectory AutoindexCrawlerService could not fetch at all (transient 5xx)
    // leaves the crawl's own entries incomplete, even though depthLimitReached/entryLimitReached
    // both stay false - a distinct reason from truncation with the same consequence for cleanup.
    serve(
        "/files/",
        "text/html",
        ("<html><head><title>Index of /files/</title></head><body><ul>"
                + "<li><a href=\"root.txt\">root.txt</a></li>"
                + "<li><a href=\"sub/\">sub/</a></li>"
                + "</ul></body></html>")
            .getBytes(StandardCharsets.UTF_8));
    serve("/files/root.txt", "text/plain", "Wurzel.".getBytes(StandardCharsets.UTF_8));
    server.createContext(
        "/files/sub/",
        exchange -> {
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });
    when(fileProcessingService.processUrlFile(
            any(),
            anyString(),
            anyString(),
            any(),
            anyLong(),
            eq(library),
            eq(DocumentSourceType.HTTP_DIRECTORY),
            isNull(),
            isNull(),
            any()))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute();

    verify(indexingRunEventRepository, timeout(5000))
        .save(argThat(categoryIs(IndexingEventCategory.REJECTED)));
    verifyNoInteractions(staleDocumentCleanupService);
  }

  @Test
  void aRootListingWithZeroEntriesStillCallsCleanupButWithAnEmptySet() throws IOException {
    // #886 review: a root page answering with an empty (but genuinely 200, well-formed) listing -
    // e.g. a maintenance page mistaken for the real directory - must not be read as "every
    // document vanished". The guard against an empty currentUrls lives inside
    // StaleDocumentCleanupService#cleanupVanished itself (see its own Javadoc), not in this
    // executor - this proves the executor still hands the (empty) set through rather than
    // special-casing it here too.
    serve(
        "/files/",
        "text/html",
        "<html><head><title>Index of /files/</title></head><body><ul></ul></body></html>"
            .getBytes(StandardCharsets.UTF_8));

    execute();

    verify(staleDocumentCleanupService, timeout(5000))
        .cleanupVanished(
            eq(library), eq(DocumentSourceType.HTTP_DIRECTORY), eq(Set.of()), any(), any(), any());
  }

  @Test
  void aLinkThatLeavesTheStartUrlIsRecordedAsARejectedEventWithItsOwnRawAddress()
      throws IOException {
    // A link the crawler refused to follow (AutoindexCrawlerService#staysUnderBase) must not be
    // silently dropped - it becomes its own REJECTED event carrying the raw link, so an operator
    // can see what the crawl left out, same as an oversized or unsupported entry.
    serve(
        "/files/",
        "text/html",
        ("<html><head><title>Index of /files/</title></head><body><ul>"
                + "<li><a href=\"%2E%2E/intern/\">up</a></li>"
                + "<li><a href=\"oeffentlich.txt\">oeffentlich.txt</a></li>"
                + "</ul></body></html>")
            .getBytes(StandardCharsets.UTF_8));
    serve("/files/oeffentlich.txt", "text/plain", "Inhalt.".getBytes(StandardCharsets.UTF_8));
    when(fileProcessingService.processUrlFile(
            any(),
            anyString(),
            anyString(),
            any(),
            anyLong(),
            eq(library),
            eq(DocumentSourceType.HTTP_DIRECTORY),
            isNull(),
            isNull(),
            any()))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute();

    verify(indexingRunEventRepository, timeout(5000))
        .save(
            argThat(
                event ->
                    event != null
                        && event.getCategory() == IndexingEventCategory.REJECTED
                        && event.getMessage().contains("nicht verfolgt")
                        && event.getReference().contains("%2E%2E")));
  }

  private static org.mockito.ArgumentMatcher<IndexingRunEvent> categoryIs(
      IndexingEventCategory category) {
    return event -> event != null && event.getCategory() == category;
  }
}
