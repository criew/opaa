package io.opaa.indexing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryFolderService;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.observability.IndexingMetrics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit-level coverage of {@link AsyncIndexingExecutor} (FILESYSTEM). Uses a real {@link
 * DocumentService} against a real {@code @TempDir} - {@code discoverFiles} is a plain filesystem
 * walk, cheaper to run for real than to mock - while {@link FileProcessingService} stays mocked:
 * this class's own job is discovering files and reacting to what {@code processFile} reports, not
 * re-testing parsing/chunking/embedding (that belongs to {@link FileProcessingServiceTest}).
 */
class AsyncIndexingExecutorTest {

  @TempDir Path documentDir;

  private FileProcessingService fileProcessingService;
  private IndexingJobService indexingJobService;
  private IndexingRunEventRepository indexingRunEventRepository;
  private LibraryStorageQuotaService storageQuotaService;
  private LibraryFolderService folderService;
  private StaleDocumentCleanupService staleDocumentCleanupService;
  private AsyncIndexingExecutor executor;
  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() {
    fileProcessingService = mock(FileProcessingService.class);
    indexingJobService = mock(IndexingJobService.class);
    indexingRunEventRepository = mock(IndexingRunEventRepository.class);
    storageQuotaService = mock(LibraryStorageQuotaService.class);
    folderService = mock(LibraryFolderService.class);
    staleDocumentCleanupService = mock(StaleDocumentCleanupService.class);
    FilesystemPathAllowlist allowlist = mock(FilesystemPathAllowlist.class);
    when(allowlist.isAllowed(any())).thenReturn(true);

    library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Bibliothek",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.FILESYSTEM,
            documentDir.toAbsolutePath().toString(),
            null,
            null,
            null,
            false);

    executor =
        new AsyncIndexingExecutor(
            new DocumentService(),
            fileProcessingService,
            indexingJobService,
            allowlist,
            indexingRunEventRepository,
            storageQuotaService,
            folderService,
            staleDocumentCleanupService);
  }

  @Test
  void aFileOverTheLibraryStorageQuotaIsSkippedAndRecordedAsARejectedEvent() throws IOException {
    // #119, PR #700 review finding 4: the QUOTA_EXCEEDED branch is identical in shape to
    // RssFeedIndexingExecutorTest's own coverage of the same FileProcessingResult, exercised here
    // for the FILESYSTEM connector specifically.
    Path file = documentDir.resolve("over-quota.txt");
    Files.writeString(file, "content");

    when(fileProcessingService.processFile(eq(file), eq(library), isNull()))
        .thenReturn(FileProcessingResult.QUOTA_EXCEEDED);
    when(storageQuotaService.quotaExceededMessage(library.getId()))
        .thenReturn("Speicherkontingent der Bibliothek erschöpft (10,0 GB von 10,0 GB belegt)");

    executor.execute(UUID.randomUUID(), library);

    String expectedMessage =
        "Speicherkontingent der Bibliothek erschöpft (10,0 GB von 10,0 GB belegt)";
    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(0), eq(0), eq(1), anyInt());
    verify(indexingRunEventRepository, timeout(2000))
        .save(
            argThat(
                event ->
                    event.getCategory() == IndexingEventCategory.REJECTED
                        && "over-quota.txt".equals(event.getReference())
                        && expectedMessage.equals(event.getMessage())));
  }

  @Test
  void aScanPdfWithoutExtractableTextIsSkippedAndRecordedAsARejectedEvent() throws IOException {
    // #1055: FileProcessingResult#NO_EXTRACTABLE_TEXT is reported the same way QUOTA_EXCEEDED is -
    // counted as skipped, not as processed, and logged by name like any other rejected file.
    Path file = documentDir.resolve("scan.txt");
    Files.writeString(file, "content");

    when(fileProcessingService.processFile(eq(file), eq(library), isNull()))
        .thenReturn(FileProcessingResult.NO_EXTRACTABLE_TEXT);

    executor.execute(UUID.randomUUID(), library);

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(0), eq(0), eq(1), anyInt());
    verify(indexingRunEventRepository, timeout(2000))
        .save(
            argThat(
                event ->
                    event.getCategory() == IndexingEventCategory.REJECTED
                        && "scan.txt".equals(event.getReference())
                        && DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE.equals(event.getMessage())));
  }

  @Test
  void aRealScanPdfEndToEndIsRejectedWithoutMockingTheFileProcessingServiceSeam()
      throws IOException {
    // #1090 review finding 3: aScanPdfWithoutExtractableTextIsSkippedAndRecordedAsARejectedEvent
    // above mocks both sides of the FileProcessingService seam (what it returns and how the
    // executor reacts) - this test instead wires a real FileProcessingService (only its own
    // dependencies mocked, same pattern as FileProcessingServiceTest's scan-detection test) so the
    // real NO_EXTRACTABLE_TEXT return value is exercised, not just asserted-away by a stub.
    Path file = documentDir.resolve("scan.pdf");
    Files.writeString(file, "%PDF-1.4\n%mock-pdf-body-for-magic-byte-detection");

    DocumentRepository documentRepository = mock(DocumentRepository.class);
    when(documentRepository.findByLibraryIdAndFilePath(eq(library.getId()), any()))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
    when(documentRepository.markFailed(any(), any())).thenReturn(1);
    org.springframework.ai.vectorstore.VectorStore vectorStore =
        mock(org.springframework.ai.vectorstore.VectorStore.class);
    LibraryStorageQuotaService realFlowQuotaService = mock(LibraryStorageQuotaService.class);
    when(realFlowQuotaService.wouldExceedQuota(any(), anyLong())).thenReturn(false);

    // Only #parseDocument is stubbed - real Tika parsing of a not-structurally-valid PDF would
    // throw, which is irrelevant to what this test exercises (see FileProcessingServiceTest's own
    // identical spy for the same reasoning). #isTextlessPdf and its underlying content-type
    // detection run for real, against the file's actual bytes on disk.
    DocumentService scanDetectingDocumentService = org.mockito.Mockito.spy(new DocumentService());
    org.mockito.Mockito.doReturn(List.of(new org.springframework.ai.document.Document("")))
        .when(scanDetectingDocumentService)
        .parseDocument(file);

    IndexingProperties indexingProperties =
        new IndexingProperties(1000, 0, 50, null, null, null, null, null, null, 1);
    FileProcessingService realFileProcessingService =
        new FileProcessingService(
            scanDetectingDocumentService,
            new ChunkingService(indexingProperties),
            documentRepository,
            new VectorChunkStore(
                vectorStore,
                mock(org.springframework.ai.embedding.EmbeddingModel.class),
                mock(org.springframework.ai.embedding.BatchingStrategy.class),
                mock(VectorStoreWriter.class),
                mock(FullTextChunkStore.class)),
            new ChecksumService(),
            new IndexingMetrics(new SimpleMeterRegistry()),
            realFlowQuotaService,
            indexingProperties,
            Runnable::run);

    FilesystemPathAllowlist realFlowAllowlist = mock(FilesystemPathAllowlist.class);
    when(realFlowAllowlist.isAllowed(any())).thenReturn(true);
    AsyncIndexingExecutor realFlowExecutor =
        new AsyncIndexingExecutor(
            new DocumentService(),
            realFileProcessingService,
            indexingJobService,
            realFlowAllowlist,
            indexingRunEventRepository,
            realFlowQuotaService,
            folderService,
            staleDocumentCleanupService);

    realFlowExecutor.execute(UUID.randomUUID(), library);

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(0), eq(0), eq(1), anyInt());
    verify(indexingRunEventRepository, timeout(2000))
        .save(
            argThat(
                event ->
                    event.getCategory() == IndexingEventCategory.REJECTED
                        && "scan.pdf".equals(event.getReference())
                        && DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE.equals(event.getMessage())));
  }

  @Test
  void aProcessedFileIsCountedNormallyWhenTheQuotaIsNotExceeded() throws IOException {
    Path file = documentDir.resolve("ok.txt");
    Files.writeString(file, "content");

    when(fileProcessingService.processFile(eq(file), eq(library), isNull()))
        .thenReturn(FileProcessingResult.PROCESSED);

    executor.execute(UUID.randomUUID(), library);

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(0), anyInt());
    verify(indexingRunEventRepository, timeout(2000).times(0)).save(any());
  }
}
