package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.indexing.AttachmentOutcome;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingOutcomes;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.IndexingEventCategory;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.IndexingRunEventRecorder;
import io.opaa.indexing.IndexingRunEventRepository;
import io.opaa.indexing.IndexingRunProgress;
import io.opaa.indexing.SourceDocumentContext;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryStorageQuotaService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What one {@link IndexingRun} offers a connector body, over mocked collaborators. */
class IndexingRunTest {

  private final IndexingJobService jobService = mock(IndexingJobService.class);
  private final IndexingRunEventRepository eventRepository = mock(IndexingRunEventRepository.class);
  private final DocumentRepository documentRepository = mock(DocumentRepository.class);
  private final LibraryStorageQuotaService quotaService = mock(LibraryStorageQuotaService.class);
  private final UUID jobId = UUID.randomUUID();
  private final KnowledgeLibrary library =
      KnowledgeLibrary.ownedByUser(
          UUID.randomUUID(),
          "Bibliothek",
          null,
          UUID.randomUUID(),
          LibraryVisibility.PRIVATE,
          false,
          DocumentSourceType.HTTP_DIRECTORY,
          null,
          "https://host/",
          null,
          null,
          false);
  private final IndexingRun run =
      new IndexingRun(
          jobId,
          library,
          IndexingRunMode.FULL,
          DocumentSourceType.HTTP_DIRECTORY,
          new IndexingRunProgress(jobService, jobId),
          new IndexingRunEventRecorder(eventRepository, jobService, jobId),
          documentRepository,
          quotaService);

  // --- the change check --------------------------------------------------------------------

  @Test
  void isUnchangedTreatsABlankOrMissingRemoteVersionAsUnknownAndAlwaysRefetches() {
    // The <ul>-based autoindex layouts never report a lastModified at all - two blank strings
    // comparing equal would mean such a source is fetched once and never again.
    Document existing = indexedDocument("https://host/file.txt", "");
    when(documentRepository.findByLibraryIdAndFilePath(library.getId(), "https://host/file.txt"))
        .thenReturn(Optional.of(existing));

    assertThat(run.isUnchanged("https://host/file.txt", "")).isFalse();
    assertThat(run.isUnchanged("https://host/file.txt", null)).isFalse();
  }

  @Test
  void isUnchangedIsTrueForAMatchingRemoteVersionOfAnIndexedDocumentOnly() {
    Document existing = indexedDocument("https://host/file.txt", "2025-06-14 09:00");
    when(documentRepository.findByLibraryIdAndFilePath(library.getId(), "https://host/file.txt"))
        .thenReturn(Optional.of(existing));

    assertThat(run.isUnchanged("https://host/file.txt", "2025-06-14 09:00")).isTrue();
    assertThat(run.isUnchanged("https://host/file.txt", "2025-06-15 09:00")).isFalse();

    existing.setStatus(DocumentStatus.FAILED);
    assertThat(run.isUnchanged("https://host/file.txt", "2025-06-14 09:00"))
        .as("a failed document is retried however unchanged its source says it is")
        .isFalse();
  }

  @Test
  void isUnchangedLooksUpTheDocumentInTheRunsOwnLibraryOnly() {
    // The same source indexed into another library is that library's document - stubbed for a
    // foreign library id so a lookup against the wrong library would match, loudly.
    Document foreign = indexedDocument("https://host/file.txt", "2025-06-14 09:00");
    when(documentRepository.findByLibraryIdAndFilePath(UUID.randomUUID(), "https://host/file.txt"))
        .thenReturn(Optional.of(foreign));
    when(documentRepository.findByLibraryIdAndFilePath(library.getId(), "https://host/file.txt"))
        .thenReturn(Optional.empty());

    assertThat(run.isUnchanged("https://host/file.txt", "2025-06-14 09:00")).isFalse();
  }

  // --- result mapping and failures ---------------------------------------------------------

  @Test
  void recordOutcomeResolvesTheQuotaMessageForTheRunsLibrary() {
    when(quotaService.quotaExceededMessage(library.getId()))
        .thenReturn("Speicherkontingent der Bibliothek erschöpft");

    boolean processed = run.recordOutcome(FileProcessingResult.QUOTA_EXCEEDED, "datei.txt");

    assertThat(processed).isFalse();
    verify(eventRepository)
        .save(
            argThat(
                event ->
                    event.getCategory() == IndexingEventCategory.REJECTED
                        && "Speicherkontingent der Bibliothek erschöpft".equals(event.getMessage())
                        && "datei.txt".equals(event.getReference())));
    assertThat(run.progress().skippedCount()).isEqualTo(1);
  }

  @Test
  void recordFailureCountsTheItemAsFailedAndRecordsTheSharedErrorMessage() {
    run.recordFailure("datei.txt", new IllegalStateException("boom"));

    assertThat(run.progress().failedCount()).isEqualTo(1);
    verify(eventRepository)
        .save(
            argThat(
                event ->
                    event.getCategory() == IndexingEventCategory.ERROR
                        && FileProcessingOutcomes.FAILED_MESSAGE.equals(event.getMessage())
                        && "datei.txt".equals(event.getReference())));
  }

  // --- the reconciliation set --------------------------------------------------------------

  @Test
  void theReconciliationSetTellsPresentFromReprocessedAndForgetsWhatTheSourceWithdrew() {
    run.markPresent("a");
    run.markReprocessed("b");
    run.markPresent("c");
    run.markAbsent("c");

    assertThat(run.currentPaths()).containsExactlyInAnyOrder("a", "b");
    assertThat(run.reprocessedPaths()).containsExactly("b");
  }

  @Test
  void theAttachmentAccessFeedsTheReconciliationSetAndCarriesItsContext() {
    SourceDocumentContext context = new SourceDocumentContext("ENG", "Handbuch");
    ReconcilingAttachmentAccess access = run.attachmentAccess(context);

    access.recordIndexedAttachment("https://host/anlage.pdf", true);
    access.recordIndexedAttachment("https://host/unveraendert.pdf", false);
    access.progress().recordAttachment(AttachmentOutcome.PROCESSED);
    access.markDeferred();

    assertThat(access.targetLibrary()).isSameAs(library);
    assertThat(access.sourceContext()).isEqualTo(context);
    assertThat(run.attachmentAccess().sourceContext()).isEqualTo(SourceDocumentContext.NONE);
    assertThat(run.currentPaths())
        .containsExactlyInAnyOrder("https://host/anlage.pdf", "https://host/unveraendert.pdf");
    assertThat(run.reprocessedPaths()).containsExactly("https://host/anlage.pdf");
    assertThat(run.progress().attachmentsProcessed()).isEqualTo(1);
  }

  private Document indexedDocument(String filePath, String lastModifiedRemote) {
    Document document = new Document("file.txt", filePath, "text/plain", 1L);
    document.setLibraryId(library.getId());
    document.setStatus(DocumentStatus.INDEXED);
    document.setLastModifiedRemote(lastModifiedRemote);
    return document;
  }
}
