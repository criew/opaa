package io.opaa.indexing.source.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.indexing.AttachmentOutcome;
import io.opaa.indexing.ChecksumService;
import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentIngests;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.DocumentService;
import io.opaa.indexing.EmbeddingRateEstimator;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.FullTextChunkStore;
import io.opaa.indexing.IndexingEventCategory;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.IndexingRunCost;
import io.opaa.indexing.IndexingRunEventRepository;
import io.opaa.indexing.StaleDocumentCleanupService;
import io.opaa.indexing.TestPipelineRegistries;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.indexing.VectorStoreWriter;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.attachment.AttachmentAccess;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryFolderService;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.observability.IndexingMetrics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Unit-level coverage of {@link AsyncIndexingExecutor} (FILESYSTEM). Uses a real {@link
 * DocumentService} against a real {@code @TempDir} - {@code discoverFiles} is a plain filesystem
 * walk, cheaper to run for real than to mock - while {@link FileProcessingService} stays mocked:
 * this class's own job is discovering files and reacting to what {@code processFile} reports, not
 * re-testing parsing/chunking/embedding (that belongs to {@code FileProcessingServiceTest}). The
 * reconciliation is a spy over the real service, so what the executor hands over and what the
 * service preserves from the database are both observable.
 */
class AsyncIndexingExecutorTest {

  @TempDir Path documentDir;

  private FileProcessingService fileProcessingService;
  private IndexingJobService indexingJobService;
  private IndexingRunEventRepository indexingRunEventRepository;
  private LibraryStorageQuotaService storageQuotaService;
  private LibraryFolderService folderService;
  private StaleDocumentCleanupService staleDocumentCleanupService;
  private DocumentRepository documentRepository;
  private AsyncIndexingExecutor executor;
  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() throws Exception {
    fileProcessingService = mock(FileProcessingService.class);
    indexingJobService = mock(IndexingJobService.class);
    indexingRunEventRepository = mock(IndexingRunEventRepository.class);
    storageQuotaService = mock(LibraryStorageQuotaService.class);
    folderService = mock(LibraryFolderService.class);
    documentRepository = mock(DocumentRepository.class);
    when(documentRepository.findByLibraryIdAndSourceType(any(), any())).thenReturn(List.of());
    staleDocumentCleanupService =
        spy(new StaleDocumentCleanupService(documentRepository, mock(VectorChunkStore.class)));
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
            allowlist,
            folderService,
            runTemplate(storageQuotaService, documentRepository));
  }

  private IndexingRunTemplate runTemplate(
      LibraryStorageQuotaService quotaService, DocumentRepository repository) {
    return new IndexingRunTemplate(
        indexingJobService,
        indexingRunEventRepository,
        staleDocumentCleanupService,
        repository,
        quotaService);
  }

  @Test
  void aFileOverTheLibraryStorageQuotaIsSkippedAndRecordedAsARejectedEvent() throws IOException {
    Path file = documentDir.resolve("over-quota.txt");
    Files.writeString(file, "content");

    when(fileProcessingService.ingest(
            DocumentIngests.that().file().file(file).in(library).inFolder(null).match(), any()))
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
                        && "over-quota.txt".equals(event.getReference())
                        && expectedMessage.equals(event.getMessage())));
  }

  @Test
  void aScanPdfWithoutExtractableTextIsSkippedAndRecordedAsARejectedEvent() throws IOException {
    // FileProcessingResult#NO_EXTRACTABLE_TEXT is reported the same way QUOTA_EXCEEDED is -
    // counted as skipped, not as processed, and logged by name like any other rejected file.
    Path file = documentDir.resolve("scan.txt");
    Files.writeString(file, "content");

    when(fileProcessingService.ingest(
            DocumentIngests.that().file().file(file).in(library).inFolder(null).match(), any()))
        .thenReturn(FileProcessingResult.NO_EXTRACTABLE_TEXT);

    executor.execute(UUID.randomUUID(), library, IndexingRunMode.FULL);

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
  void aFileThePipelineCannotParseAtAllIsCountedAsFailedAndRecordedAsAnErrorEvent()
      throws IOException {
    // FileProcessingResult#FAILED (NO_CONTENT - the pipeline could not parse the document at all)
    // is reported like the catch block's own ERROR event, never silently counted as processed.
    Path file = documentDir.resolve("corrupt.txt");
    Files.writeString(file, "content");

    when(fileProcessingService.ingest(
            DocumentIngests.that().file().file(file).in(library).inFolder(null).match(), any()))
        .thenReturn(FileProcessingResult.FAILED);

    executor.execute(UUID.randomUUID(), library, IndexingRunMode.FULL);

    // eq(0) on documentsIndexedTotal, not anyInt(): a document the pipeline could not parse at
    // all must not inflate the run's indexed-total either.
    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(0), eq(1), eq(0), eq(0));
    verify(indexingRunEventRepository, timeout(2000))
        .save(
            argThat(
                event ->
                    event.getCategory() == IndexingEventCategory.ERROR
                        && "corrupt.txt".equals(event.getReference())
                        && "Verarbeitung fehlgeschlagen".equals(event.getMessage())));
  }

  @Test
  void aRealScanPdfEndToEndIsRejectedWithoutMockingTheFileProcessingServiceSeam()
      throws IOException {
    // aScanPdfWithoutExtractableTextIsSkippedAndRecordedAsARejectedEvent above mocks both sides
    // of the FileProcessingService seam - this test instead wires a real FileProcessingService
    // (only its own dependencies mocked, same pattern as FileProcessingServiceTest's scan-detection
    // test) so the real NO_EXTRACTABLE_TEXT return value is exercised, not just asserted-away.
    Path file = documentDir.resolve("scan.pdf");
    Files.writeString(file, "%PDF-1.4\n%mock-pdf-body-for-magic-byte-detection");

    DocumentRepository realFlowDocumentRepository = mock(DocumentRepository.class);
    when(realFlowDocumentRepository.findByLibraryIdAndFilePath(eq(library.getId()), any()))
        .thenReturn(Optional.empty());
    when(realFlowDocumentRepository.save(any(Document.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(realFlowDocumentRepository.markFailed(any(), any())).thenReturn(1);
    when(realFlowDocumentRepository.markFailedWithoutChunks(any(), any())).thenReturn(1);
    when(realFlowDocumentRepository.findByLibraryIdAndSourceType(any(), any()))
        .thenReturn(List.of());
    org.springframework.ai.vectorstore.VectorStore vectorStore =
        mock(org.springframework.ai.vectorstore.VectorStore.class);
    LibraryStorageQuotaService realFlowQuotaService = mock(LibraryStorageQuotaService.class);
    when(realFlowQuotaService.wouldExceedQuota(any(), anyLong())).thenReturn(false);

    // Only #parseDocument is stubbed - real Tika parsing of a not-structurally-valid PDF would
    // throw, which is irrelevant to what this test exercises (see FileProcessingServiceTest's own
    // identical spy for the same reasoning). Everything after parsing runs for real.
    DocumentService scanDetectingDocumentService = org.mockito.Mockito.spy(new DocumentService());
    org.mockito.Mockito.doReturn(List.of(new org.springframework.ai.document.Document("")))
        .when(scanDetectingDocumentService)
        .parseDocument(file);

    IndexingProperties indexingProperties =
        new IndexingProperties(1000, 0, 50, null, null, null, null, 1);
    FileProcessingService realFileProcessingService =
        new FileProcessingService(
            TestPipelineRegistries.fallbackOnly(
                scanDetectingDocumentService, new ChunkingService(indexingProperties)),
            realFlowDocumentRepository,
            new VectorChunkStore(
                vectorStore,
                mock(org.springframework.ai.embedding.EmbeddingModel.class),
                mock(org.springframework.ai.embedding.BatchingStrategy.class),
                mock(VectorStoreWriter.class),
                mock(FullTextChunkStore.class),
                new EmbeddingRateEstimator(4.0)),
            new ChecksumService(),
            new IndexingMetrics(new SimpleMeterRegistry()),
            realFlowQuotaService,
            indexingProperties,
            Runnable::run,
            org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class),
            new io.opaa.indexing.source.attachment.AttachmentDownloadLimits(0, 0, 0, ""),
            io.opaa.indexing.TestDocumentMetadataServices.returningEmpty(),
            io.opaa.indexing.TestDocumentMetadataServices.notExtracting());

    FilesystemPathAllowlist realFlowAllowlist = mock(FilesystemPathAllowlist.class);
    when(realFlowAllowlist.isAllowed(any())).thenReturn(true);
    AsyncIndexingExecutor realFlowExecutor =
        new AsyncIndexingExecutor(
            new DocumentService(),
            realFileProcessingService,
            realFlowAllowlist,
            folderService,
            runTemplate(realFlowQuotaService, realFlowDocumentRepository));

    realFlowExecutor.execute(UUID.randomUUID(), library, IndexingRunMode.FULL);

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

    when(fileProcessingService.ingest(
            DocumentIngests.that().file().file(file).in(library).inFolder(null).match(), any()))
        .thenReturn(FileProcessingResult.PROCESSED);

    executor.execute(UUID.randomUUID(), library, IndexingRunMode.FULL);

    verify(indexingJobService, timeout(2000)).completeJob(any(), eq(1), eq(0), eq(0), anyInt());
    verify(indexingRunEventRepository, timeout(2000).times(0)).save(any());
  }

  @Test
  void aRemovedAttachmentOfAReprocessedMailIsCleanedUpAsVanished() throws IOException {
    // ADR-0022, Entscheidung 3: for a mail that was actually re-parsed this run, only the
    // attachments the attachment path re-reported count as present - a bestand row of a since-
    // removed attachment must NOT survive just because its parent's file still exists.
    Path mailFile = documentDir.resolve("mail.eml");
    Files.writeString(mailFile, "mail content");
    String mailPath = mailFile.toAbsolutePath().toString();
    String keptPath = mailPath + "/0/behalten.pdf";
    String removedPath = mailPath + "/1/entfernt.pdf";

    Document mailDoc = filesystemDocument("mail.eml", mailPath, null);
    Document keptDoc = filesystemDocument("behalten.pdf", keptPath, mailDoc.getId());
    Document removedDoc = filesystemDocument("entfernt.pdf", removedPath, mailDoc.getId());
    when(documentRepository.findByLibraryIdAndSourceType(
            library.getId(), DocumentSourceType.FILESYSTEM))
        .thenReturn(List.of(mailDoc, keptDoc, removedDoc));

    when(fileProcessingService.ingest(
            DocumentIngests.that().file().file(mailFile).in(library).inFolder(null).match(), any()))
        .thenAnswer(
            invocation -> {
              AttachmentAccess access = invocation.getArgument(1);
              access.recordIndexedAttachment(keptPath, true);
              return FileProcessingResult.PROCESSED;
            });

    executor.execute(UUID.randomUUID(), library, IndexingRunMode.FULL);

    Set<String> currentFilePaths = capturedCurrentFilePaths();
    assertThat(currentFilePaths).contains(mailPath, keptPath);
    assertThat(currentFilePaths).doesNotContain(removedPath);
    assertThat(capturedReprocessedPaths()).contains(mailPath, keptPath);
    verify(documentRepository).delete(removedDoc);
    verify(documentRepository, never()).delete(keptDoc);
    verify(documentRepository, never()).delete(mailDoc);
  }

  @Test
  void attachmentsOfAChecksumSkippedMailArePreservedRecursivelyFromTheDatabase()
      throws IOException {
    // The Nachtragsfall of ADR-0022, Entscheidung 3: an unchanged (checksum-skipped) mail is
    // never re-parsed, so the executor reports it present but not reprocessed - and the
    // reconciliation preserves its attachment rows, including a grandchild of a nested mail,
    // from the database regardless of row order.
    Path mailFile = documentDir.resolve("unveraendert.eml");
    Files.writeString(mailFile, "mail content");
    String mailPath = mailFile.toAbsolutePath().toString();
    String innerMailPath = mailPath + "/0/weitergeleitet.eml";
    String grandchildPath = innerMailPath + "/0/anlage.pdf";

    Document mailDoc = filesystemDocument("unveraendert.eml", mailPath, null);
    Document innerMailDoc =
        filesystemDocument("weitergeleitet.eml", innerMailPath, mailDoc.getId());
    Document grandchildDoc = filesystemDocument("anlage.pdf", grandchildPath, innerMailDoc.getId());
    when(documentRepository.findByLibraryIdAndSourceType(
            library.getId(), DocumentSourceType.FILESYSTEM))
        .thenReturn(List.of(grandchildDoc, mailDoc, innerMailDoc));

    when(fileProcessingService.ingest(
            DocumentIngests.that().file().file(mailFile).in(library).inFolder(null).match(), any()))
        .thenReturn(FileProcessingResult.SKIPPED);

    executor.execute(UUID.randomUUID(), library, IndexingRunMode.FULL);

    assertThat(capturedCurrentFilePaths()).contains(mailPath);
    assertThat(capturedReprocessedPaths()).doesNotContain(mailPath);
    verify(documentRepository, never()).delete(any(Document.class));
  }

  @Test
  void aGrandchildOfAnUnchangedInnerMailSurvivesTheOuterMailsReprocessing() throws IOException {
    // The mixed case: the outer mail was re-parsed (its direct attachment set is authoritative
    // from the attachment path's own recording), but the inner, nested mail was merely confirmed
    // unchanged by checksum - so ITS children were not rediscovered and are preserved from the
    // database, again independent of row order.
    Path mailFile = documentDir.resolve("aussen.eml");
    Files.writeString(mailFile, "mail content");
    String mailPath = mailFile.toAbsolutePath().toString();
    String innerMailPath = mailPath + "/0/weitergeleitet.eml";
    String grandchildPath = innerMailPath + "/0/anlage.pdf";

    Document mailDoc = filesystemDocument("aussen.eml", mailPath, null);
    Document innerMailDoc =
        filesystemDocument("weitergeleitet.eml", innerMailPath, mailDoc.getId());
    Document grandchildDoc = filesystemDocument("anlage.pdf", grandchildPath, innerMailDoc.getId());
    when(documentRepository.findByLibraryIdAndSourceType(
            library.getId(), DocumentSourceType.FILESYSTEM))
        .thenReturn(List.of(grandchildDoc, mailDoc, innerMailDoc));

    when(fileProcessingService.ingest(
            DocumentIngests.that().file().file(mailFile).in(library).inFolder(null).match(), any()))
        .thenAnswer(
            invocation -> {
              AttachmentAccess access = invocation.getArgument(1);
              // The inner mail was confirmed unchanged (SKIPPED), not re-parsed.
              access.recordIndexedAttachment(innerMailPath, false);
              return FileProcessingResult.PROCESSED;
            });

    executor.execute(UUID.randomUUID(), library, IndexingRunMode.FULL);

    assertThat(capturedCurrentFilePaths()).contains(mailPath, innerMailPath);
    assertThat(capturedReprocessedPaths()).contains(mailPath).doesNotContain(innerMailPath);
    verify(documentRepository, never()).delete(any(Document.class));
  }

  @Test
  void aLibraryDeletedDuringTheRunFailsWithAGermanMessageNotTheJdbcOne() throws IOException {
    // A foreign key to the library breaking mid-run - simulated at the first job write, the
    // earliest point the frame sees such a failure - is translated by the frame, the same for
    // every connector.
    Files.writeString(documentDir.resolve("datei.txt"), "content");
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
    // A FILESYSTEM run writes its cost like every connector: the attachment share comes from what
    // the attachment path counted, requests and throttles stay 0 for a source without a meter.
    Path mailFile = documentDir.resolve("mail.eml");
    Files.writeString(mailFile, "mail content");
    UUID jobId = UUID.randomUUID();
    when(fileProcessingService.ingest(
            DocumentIngests.that().file().file(mailFile).in(library).inFolder(null).match(), any()))
        .thenAnswer(
            invocation -> {
              AttachmentAccess access = invocation.getArgument(1);
              access.progress().recordAttachment(AttachmentOutcome.PROCESSED);
              access.progress().recordAttachment(AttachmentOutcome.PROCESSED);
              access.progress().recordAttachment(AttachmentOutcome.SKIPPED);
              access.progress().recordAttachment(AttachmentOutcome.FAILED);
              return FileProcessingResult.PROCESSED;
            });

    executor.execute(jobId, library, IndexingRunMode.FULL);

    ArgumentCaptor<IndexingRunCost> cost = ArgumentCaptor.forClass(IndexingRunCost.class);
    verify(indexingJobService, timeout(2000)).recordRunMetrics(eq(jobId), cost.capture());
    assertThat(cost.getValue()).isEqualTo(new IndexingRunCost(0, 0, 0L, 2, 1, 1, false));
    verify(indexingJobService).completeJob(jobId, 1, 0, 0, 3);
  }

  private Document filesystemDocument(String fileName, String filePath, UUID parentDocumentId) {
    Document document = new Document(fileName, filePath, "application/octet-stream", 1L);
    document.setLibraryId(library.getId());
    document.setParentDocumentId(parentDocumentId);
    return document;
  }

  @SuppressWarnings("unchecked")
  private Set<String> capturedCurrentFilePaths() {
    ArgumentCaptor<Set<String>> pathsCaptor = ArgumentCaptor.forClass(Set.class);
    verify(staleDocumentCleanupService, timeout(2000))
        .reconcile(
            eq(library),
            eq(DocumentSourceType.FILESYSTEM),
            pathsCaptor.capture(),
            any(),
            any(),
            any(),
            any());
    return pathsCaptor.getValue();
  }

  @SuppressWarnings("unchecked")
  private Set<String> capturedReprocessedPaths() {
    ArgumentCaptor<Set<String>> pathsCaptor = ArgumentCaptor.forClass(Set.class);
    verify(staleDocumentCleanupService, timeout(2000))
        .reconcile(
            eq(library),
            eq(DocumentSourceType.FILESYSTEM),
            any(),
            pathsCaptor.capture(),
            any(),
            any(),
            any());
    return pathsCaptor.getValue();
  }
}
