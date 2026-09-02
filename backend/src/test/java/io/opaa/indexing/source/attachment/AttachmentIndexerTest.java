package io.opaa.indexing.source.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.IndexingEventCategory;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.IndexingRunEventRecorder;
import io.opaa.indexing.IndexingRunEventRepository;
import io.opaa.indexing.IndexingRunProgress;
import io.opaa.indexing.source.rss.RssFeedRunContext;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.sourceaccess.BoundedDownloader;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit-level coverage of {@link AttachmentIndexer}, split out from {@code
 * RssFeedIndexingExecutorTest}'s heavier end-to-end coverage (real HTTP server, real {@link
 * BoundedDownloader}) - here {@link BoundedDownloader} and {@link FileProcessingService} are both
 * mocked, isolating the one branch that needs its own targeted proof.
 */
class AttachmentIndexerTest {

  @TempDir Path tempDir;

  private BoundedDownloader attachmentDownloader;
  private FileProcessingService fileProcessingService;
  private IndexingJobService indexingJobService;
  private IndexingRunEventRepository indexingRunEventRepository;
  private AttachmentIndexer indexer;
  private RssFeedRunContext ctx;

  @BeforeEach
  void setUp() {
    attachmentDownloader = mock(BoundedDownloader.class);
    fileProcessingService = mock(FileProcessingService.class);
    LibraryStorageQuotaService storageQuotaService = mock(LibraryStorageQuotaService.class);
    IndexingProperties.Rss properties =
        new IndexingProperties.Rss(
            200, 10_485_760L, 5_242_880L, 0, null, null, AttachmentProfile.GENERIC, 10, 10_000);
    indexer =
        new AttachmentIndexer(
            attachmentDownloader, fileProcessingService, storageQuotaService, properties);

    indexingJobService = mock(IndexingJobService.class);
    indexingRunEventRepository = mock(IndexingRunEventRepository.class);
    UUID jobId = UUID.randomUUID();
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Bibliothek",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.RSS_FEED,
            null,
            null,
            null,
            null,
            false);
    HttpClient httpClient = HttpClient.newHttpClient();
    ctx =
        new RssFeedRunContext(
            httpClient,
            httpClient,
            library,
            null,
            "https://example.org/feed.xml",
            new IndexingRunProgress(indexingJobService, jobId),
            new IndexingRunEventRecorder(indexingRunEventRepository, indexingJobService, jobId),
            new AtomicBoolean(false));
  }

  @Test
  void anAttachmentThePipelineCannotParseAtAllIsRecordedAsAnErrorButNeverCountedAsARunEntry()
      throws IOException, InterruptedException {
    // #1108 review: unlike AsyncIndexingExecutor/UrlIndexingExecutor/RssFeedIndexingExecutor's own
    // entry-level FAILED handling (recordFailed() + ERROR event), an attachment is not a discrete
    // unit of the run's own processed/failed/skipped counters - so FAILED here must only defer the
    // feed's ETag persistence (anyEntryDeferred) and log an ERROR event, never call recordFailed().
    Path downloaded = tempDir.resolve("attachment.txt");
    Files.writeString(downloaded, "content");
    when(attachmentDownloader.downloadBounded(
            any(), anyString(), anyString(), anyLong(), any(), any()))
        .thenReturn(new BoundedDownloader.DownloadedFile(downloaded, "text/plain"));
    when(fileProcessingService.processUrlFile(
            any(), anyString(), anyString(), any(), anyLong(), any(), any(), any()))
        .thenReturn(FileProcessingResult.FAILED);

    indexer.indexAll(
        ctx,
        List.of(new AttachmentCandidate("https://example.org/attachment.txt", "attachment.txt")),
        "https://example.org/entry.html");

    assertThat(ctx.anyEntryDeferred()).isTrue();
    verify(indexingRunEventRepository)
        .save(
            argThat(
                event ->
                    event.getCategory() == IndexingEventCategory.ERROR
                        && "Verarbeitung der Anlage fehlgeschlagen".equals(event.getMessage())
                        && "https://example.org/attachment.txt".equals(event.getReference())));
    // No recordFailed()/recordProcessed() call on this run's own counters - an attachment failure
    // must never call completeJob with an inflated or deflated processed/failed count of its own.
    verifyNoInteractions(indexingJobService);
  }
}
