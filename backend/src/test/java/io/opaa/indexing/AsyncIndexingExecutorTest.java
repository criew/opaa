package io.opaa.indexing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryFolderService;
import io.opaa.library.LibraryStorageQuotaService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
  private AsyncIndexingExecutor executor;
  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() {
    fileProcessingService = mock(FileProcessingService.class);
    indexingJobService = mock(IndexingJobService.class);
    indexingRunEventRepository = mock(IndexingRunEventRepository.class);
    storageQuotaService = mock(LibraryStorageQuotaService.class);
    folderService = mock(LibraryFolderService.class);
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
            folderService);
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
