package io.opaa.indexing.source.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.IndexingEventCategory;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.IndexingRunEventRepository;
import io.opaa.indexing.StaleDocumentCleanupService;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.sourceaccess.BoundedDownloader;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit-level coverage of {@link UrlIndexingExecutor}'s {@code FileProcessingResult#QUOTA_EXCEEDED}
 * handling (#119, PR #700 review finding 4) - {@link UrlIndexingExecutorTest} already covers this
 * class's {@code isUnchanged} logic in isolation; this class instead drives the full {@code
 * execute} flow with {@link AutoindexCrawlerService} and {@link BoundedDownloader} mocked (no live
 * HTTP server needed, unlike {@code RssFeedIndexingExecutorTest}, since both are ordinary
 * constructor dependencies here).
 */
class UrlIndexingExecutorQuotaTest {

  @TempDir Path tempDir;

  private FileProcessingService fileProcessingService;
  private IndexingJobService indexingJobService;
  private DocumentRepository documentRepository;
  private IndexingRunEventRepository indexingRunEventRepository;
  private LibraryStorageQuotaService storageQuotaService;
  private UrlIndexingExecutor executor;
  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() throws IOException, InterruptedException {
    AutoindexCrawlerService crawlerService = mock(AutoindexCrawlerService.class);
    BoundedDownloader downloader = mock(BoundedDownloader.class);
    fileProcessingService = mock(FileProcessingService.class);
    indexingJobService = mock(IndexingJobService.class);
    documentRepository = mock(DocumentRepository.class);
    indexingRunEventRepository = mock(IndexingRunEventRepository.class);
    storageQuotaService = mock(LibraryStorageQuotaService.class);

    library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Bibliothek",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.HTTP_DIRECTORY,
            null,
            "https://example.com/docs/",
            null,
            null,
            false);

    var entry =
        new AutoindexCrawlerService.CrawledFileEntry(
            "over-quota.txt", "https://example.com/docs/over-quota.txt", null, "1", "FILE", 0);
    when(crawlerService.crawl(anyString(), any(), anyInt(), any(), any(), anyBoolean()))
        .thenReturn(new AutoindexCrawlerService.CrawlResult(List.of(entry), false, false, false));

    Path downloaded = tempDir.resolve("over-quota.txt");
    Files.writeString(downloaded, "content");
    when(downloader.download(any(HttpClient.class), any(), anyString(), anyString()))
        .thenReturn(downloaded);
    // #404 review, finding 1: the executor now reads a bounded prefix to decide before ever
    // calling #download - this mock must answer it too, or the format decision sees a null
    // sample and the entry never reaches the quota check this test exercises.
    when(downloader.downloadPrefix(any(HttpClient.class), any(), anyString(), anyInt()))
        .thenReturn("content".getBytes(StandardCharsets.UTF_8));

    executor =
        new UrlIndexingExecutor(
            crawlerService,
            downloader,
            fileProcessingService,
            indexingJobService,
            documentRepository,
            indexingRunEventRepository,
            storageQuotaService,
            mock(StaleDocumentCleanupService.class));
  }

  @Test
  void aFileOverTheLibraryStorageQuotaIsSkippedAndRecordedAsARejectedEvent() throws IOException {
    when(fileProcessingService.processUrlFile(
            any(), anyString(), anyString(), any(), anyLong(), eq(library)))
        .thenReturn(FileProcessingResult.QUOTA_EXCEEDED);
    when(storageQuotaService.quotaExceededMessage(library.getId()))
        .thenReturn("Speicherkontingent der Bibliothek erschöpft (10,0 GB von 10,0 GB belegt)");

    executor.execute(UUID.randomUUID(), library, IndexingRunMode.FULL);

    String expectedMessage =
        "Speicherkontingent der Bibliothek erschöpft (10,0 GB von 10,0 GB belegt)";
    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(0), eq(0), eq(1), anyInt());
    verify(indexingRunEventRepository, timeout(2000))
        .save(
            argThat(
                event ->
                    event.getCategory() == IndexingEventCategory.REJECTED
                        && "https://example.com/docs/over-quota.txt".equals(event.getReference())
                        && expectedMessage.equals(event.getMessage())));
  }
}
