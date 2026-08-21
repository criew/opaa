package io.opaa.indexing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.library.LibraryVisibility;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link UrlIndexingExecutor#execute} end to end against a local {@code
 * com.sun.net.httpserver.HttpServer} stub, mirroring {@link RssFeedIndexingExecutorTest}'s pattern
 * - {@link AutoindexCrawlerService} and {@link UrlFileDownloader} are the real implementations
 * here, only {@link FileProcessingService}/{@link IndexingJobService}/{@link DocumentRepository}/
 * {@link IndexingRunEventRepository} are mocked.
 *
 * <p>Covers #404 review, finding 1 (the BLOCKER): a crawled entry this run ends up rejecting must
 * never reach {@link FileProcessingService#processUrlFile} and must count as skipped, not failed -
 * {@link UrlFileDownloaderTest} already proves the underlying {@code downloadPrefix} call itself
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
    when(documentRepository.findByFilePath(anyString())).thenReturn(Optional.empty());
    indexingRunEventRepository = mock(IndexingRunEventRepository.class);

    // Target validation is exercised on its own dedicated stand (TargetAddressValidatorTest) -
    // disabled here so a loopback test server is actually reachable, mirroring
    // UrlFileDownloaderTest/RssFeedIndexingExecutorTest's own setup.
    TargetAddressValidator targetAddressValidator = TargetAddressValidator.disabled();
    executor =
        new UrlIndexingExecutor(
            new AutoindexCrawlerService(targetAddressValidator),
            new UrlFileDownloader(targetAddressValidator),
            fileProcessingService,
            indexingJobService,
            documentRepository,
            indexingRunEventRepository,
            mock(LibraryStorageQuotaService.class));
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  private void serve(String path, String contentType, byte[] body) {
    server.createContext(
        path,
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", contentType);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
  }

  private void execute() {
    library.updateSourceConfiguration(null, baseUrl + "/files/", null, null, false);
    UUID jobId = UUID.randomUUID();
    executor.execute(jobId, library);
    verify(indexingJobService, timeout(5000))
        .completeJob(eq(jobId), anyInt(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void acceptsAMislabeledPdfAndReportsTheMismatchInsteadOfRejectingItByExtension()
      throws IOException {
    // The core case #404 exists for, now proven on a real request/response round trip rather than
    // only through classifyDownloadedFile in isolation.
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
            any(), anyString(), anyString(), any(), anyLong(), eq(library)))
        .thenReturn(FileProcessingResult.PROCESSED);

    execute();

    verify(fileProcessingService, timeout(5000))
        .processUrlFile(any(), eq("bescheid.csv"), anyString(), any(), anyLong(), eq(library));
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
        .processUrlFile(any(), any(), any(), any(), anyLong(), any());
    verify(indexingJobService, timeout(5000)).completeJob(any(), eq(0), eq(0), eq(1), eq(0));
    verify(indexingRunEventRepository, timeout(5000))
        .save(argThat(categoryIs(IndexingEventCategory.UNSUPPORTED_FORMAT)));
  }

  private static org.mockito.ArgumentMatcher<IndexingRunEvent> categoryIs(
      IndexingEventCategory category) {
    return event -> event != null && event.getCategory() == category;
  }
}
