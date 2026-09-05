package io.opaa.indexing.source.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.indexing.AttachmentOutcome;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.IndexingEventCategory;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.IndexingRunCost;
import io.opaa.indexing.IndexingRunEventRepository;
import io.opaa.indexing.StaleDocumentCleanupService;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.attachment.AttachmentAccess;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.sourceaccess.BoundedDownloader;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Unit-level coverage of {@link UrlIndexingExecutor}'s result handling and its share of the run
 * frame's bookkeeping, driving the full {@code execute} flow with {@link AutoindexCrawlerService}
 * and {@link BoundedDownloader} mocked (no live HTTP server needed, unlike {@code
 * UrlIndexingExecutorExecuteTest}, since both are ordinary constructor dependencies here).
 */
class UrlIndexingExecutorQuotaTest {

  private static final String ENTRY_URL = "https://example.com/docs/over-quota.txt";

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
    when(documentRepository.findByLibraryIdAndFilePath(any(), anyString()))
        .thenReturn(Optional.empty());
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
            "over-quota.txt", ENTRY_URL, null, "1", "FILE", 0);
    when(crawlerService.crawl(anyString(), any(), anyInt(), any(), any(), anyBoolean()))
        .thenReturn(
            new AutoindexCrawlerService.CrawlResult(
                List.of(entry), false, false, false, List.of()));

    Path downloaded = tempDir.resolve("over-quota.txt");
    Files.writeString(downloaded, "content");
    when(downloader.download(any(HttpClient.class), any(), anyString(), anyString(), anyLong()))
        .thenReturn(downloaded);
    // The executor reads a bounded prefix to decide before ever calling #download - this mock
    // must answer it too, or the format decision sees a null sample and the entry never reaches
    // processing.
    when(downloader.downloadPrefix(any(HttpClient.class), any(), anyString(), anyInt()))
        .thenReturn("content".getBytes(StandardCharsets.UTF_8));

    executor =
        new UrlIndexingExecutor(
            crawlerService,
            downloader,
            fileProcessingService,
            documentRepository,
            new CrawlProperties(0, 0, 0),
            mock(io.opaa.library.LibraryFolderService.class),
            new IndexingRunTemplate(
                indexingJobService,
                indexingRunEventRepository,
                mock(StaleDocumentCleanupService.class),
                documentRepository,
                storageQuotaService));
  }

  private void stubProcessUrlFile(org.mockito.stubbing.Answer<FileProcessingResult> answer)
      throws IOException {
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
        .thenAnswer(answer);
  }

  @Test
  void aFileOverTheLibraryStorageQuotaIsSkippedAndRecordedAsARejectedEvent() throws IOException {
    stubProcessUrlFile(invocation -> FileProcessingResult.QUOTA_EXCEEDED);
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
                        && ENTRY_URL.equals(event.getReference())
                        && expectedMessage.equals(event.getMessage())));
  }

  @Test
  void aLibraryDeletedDuringTheRunFailsWithAGermanMessageNotTheJdbcOne() {
    // A foreign key to the library breaking mid-run - simulated at the first job write, the
    // earliest point the frame sees such a failure - is translated by the frame, the same for
    // every connector.
    UUID jobId = UUID.randomUUID();
    doThrow(
            new DataIntegrityViolationException(
                "insert or update on table \"documents\" violates foreign key constraint"
                    + " \"fk_documents_library\""))
        .when(indexingJobService)
        .setTotalDocuments(eq(jobId), anyInt());

    executor.execute(jobId, library, IndexingRunMode.FULL);

    verify(indexingJobService, timeout(2000))
        .failJob(jobId, "Die Bibliothek wurde während des Laufs gelöscht.");
    verify(indexingJobService, never()).completeJob(any(), anyInt(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void attachmentOutcomesOfAMailAreRecordedInTheRunsCost() throws IOException {
    // An HTTP_DIRECTORY run writes its cost like every connector: the attachment share comes from
    // what the attachment path counted, requests and throttles stay 0 for a source without a
    // meter.
    UUID jobId = UUID.randomUUID();
    stubProcessUrlFile(
        invocation -> {
          AttachmentAccess access = invocation.getArgument(9);
          access.progress().recordAttachment(AttachmentOutcome.PROCESSED);
          access.progress().recordAttachment(AttachmentOutcome.SKIPPED);
          access.progress().recordAttachment(AttachmentOutcome.SKIPPED);
          access.progress().recordAttachment(AttachmentOutcome.FAILED);
          return FileProcessingResult.PROCESSED;
        });

    executor.execute(jobId, library, IndexingRunMode.FULL);

    ArgumentCaptor<IndexingRunCost> cost = ArgumentCaptor.forClass(IndexingRunCost.class);
    verify(indexingJobService, timeout(2000)).recordRunMetrics(eq(jobId), cost.capture());
    assertThat(cost.getValue()).isEqualTo(new IndexingRunCost(0, 0, 0L, 1, 2, 1, false));
    verify(indexingJobService).completeJob(jobId, 1, 0, 0, 2);
  }
}
