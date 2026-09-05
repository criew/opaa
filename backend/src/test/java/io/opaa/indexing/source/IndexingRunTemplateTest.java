package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.indexing.AttachmentOutcome;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.IndexingEventCategory;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.IndexingRunCost;
import io.opaa.indexing.IndexingRunEventRepository;
import io.opaa.indexing.StaleDocumentCleanupService;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryStorageQuotaService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The frame every connector run shares: one terminal call per run, every failure class translated
 * into the job's German message, reconciliation and assessment only where the listing and the run
 * mode allow it, and the cost written for every run that reached its body.
 */
class IndexingRunTemplateTest {

  private final IndexingJobService jobService = mock(IndexingJobService.class);
  private final IndexingRunEventRepository eventRepository = mock(IndexingRunEventRepository.class);
  private final StaleDocumentCleanupService cleanupService =
      mock(StaleDocumentCleanupService.class);
  private final DocumentRepository documentRepository = mock(DocumentRepository.class);
  private final LibraryStorageQuotaService quotaService = mock(LibraryStorageQuotaService.class);
  private final IndexingRunTemplate template =
      new IndexingRunTemplate(
          jobService, eventRepository, cleanupService, documentRepository, quotaService);
  private final SourceIndexingExecutor fullListingExecutor =
      executor(
          IndexingSourceType.FILESYSTEM,
          Map.of(IndexingRunMode.FULL, VanishedDocumentPolicy.REMOVE_ON_ABSENCE));
  private final SourceIndexingExecutor windowExecutor =
      executor(
          IndexingSourceType.RSS_FEED,
          Map.of(IndexingRunMode.INCREMENTAL, VanishedDocumentPolicy.KEEP_ON_ABSENCE));
  private final UUID jobId = UUID.randomUUID();
  private final KnowledgeLibrary library =
      KnowledgeLibrary.ownedByUser(
          UUID.randomUUID(),
          "Bibliothek",
          null,
          UUID.randomUUID(),
          LibraryVisibility.PRIVATE,
          false,
          DocumentSourceType.FILESYSTEM,
          "/srv/dokumente",
          null,
          null,
          null,
          false);

  private static SourceIndexingExecutor executor(
      IndexingSourceType type, Map<IndexingRunMode, VanishedDocumentPolicy> modes) {
    SourceIndexingExecutor executor = mock(SourceIndexingExecutor.class);
    when(executor.sourceType()).thenReturn(type);
    when(executor.runModes()).thenReturn(modes);
    return executor;
  }

  // --- the terminal call -------------------------------------------------------------------

  @Test
  void anUndeclaredRunModeFailsTheJobWithoutRunningTheBody() {
    AtomicReference<IndexingRun> seen = new AtomicReference<>();

    template.run(
        jobId,
        library,
        IndexingRunMode.INCREMENTAL,
        fullListingExecutor,
        run -> {
          seen.set(run);
          return ListingOutcome.complete();
        });

    assertThat(seen.get()).isNull();
    verify(jobService)
        .failJob(jobId, "Betriebsart INCREMENTAL wird für diesen Quellentyp nicht unterstützt");
    verify(jobService, never()).recordRunMetrics(any(), any());
    verify(jobService, never()).completeJob(any(), anyInt(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void aCompletedBodyEndsTheJobExactlyOnceAfterTheProtocolIsFinalized() {
    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> {
          run.progress().recordProcessed();
          run.progress().recordSkipped();
          run.progress().recordAttachment(AttachmentOutcome.PROCESSED);
          run.recordRequestCost(12, 1, 500L);
          return ListingOutcome.complete();
        });

    InOrder order = inOrder(jobService);
    order
        .verify(jobService)
        .recordRunMetrics(jobId, new IndexingRunCost(12, 1, 500L, 1, 0, 0, false));
    order.verify(jobService).completeJob(jobId, 1, 0, 1, 2);
    verify(jobService, never()).failJob(any(), any());
  }

  // --- failure translation -----------------------------------------------------------------

  @Test
  void aRunFailedExceptionCarriesItsOwnMessageOntoTheJob() {
    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> {
          throw new IndexingRunFailedException("Der Quellpfad ist nicht freigegeben");
        });

    verify(jobService).failJob(jobId, "Der Quellpfad ist nicht freigegeben");
    verify(jobService).recordRunMetrics(eq(jobId), any());
    verify(jobService, never()).completeJob(any(), anyInt(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void anInterruptionFailsTheJobAsInterruptedAndKeepsTheThreadsFlag() {
    try {
      template.run(
          jobId,
          library,
          IndexingRunMode.FULL,
          fullListingExecutor,
          run -> {
            throw new InterruptedException();
          });

      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
    verify(jobService).failJob(jobId, IndexingRunTemplate.INTERRUPTED_MESSAGE);
  }

  @Test
  void aBrokenForeignKeyToTheLibraryFailsTheJobAsDeletedDuringTheRun() {
    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> {
          throw new DataIntegrityViolationException(
              "insert or update on table \"documents\" violates foreign key constraint"
                  + " \"fk_documents_library\"");
        });

    verify(jobService).failJob(jobId, "Die Bibliothek wurde während des Laufs gelöscht.");
  }

  @Test
  void anyOtherExceptionFailsTheJobWithItsOwnMessage() {
    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> {
          throw new java.io.IOException("Verzeichnis nicht lesbar");
        });

    verify(jobService).failJob(jobId, "Verzeichnis nicht lesbar");
  }

  @Test
  void anExceptionWithoutAMessageStillFailsTheJob() {
    // A ConnectException from the JDK's networking stack can carry no message at all - the run
    // failed regardless, and must never end COMPLETED for lack of a text.
    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> {
          throw new java.net.ConnectException();
        });

    verify(jobService).failJob(eq(jobId), any());
    verify(jobService, never()).completeJob(any(), anyInt(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void aFailedRunNeitherReconcilesNorAssessesItsListing() {
    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> {
          run.markPresent("/srv/dokumente/a.txt");
          throw new IllegalStateException("mitten im Lauf");
        });

    verify(cleanupService, never()).reconcile(any(), any(), any(), any(), any(), any(), any());
    verify(jobService, never()).recordListingAssessment(any(), anyBoolean(), any());
  }

  // --- reconciliation and assessment -------------------------------------------------------

  @Test
  void aCompleteListingReconcilesRunsTheHookAndRecordsACompleteAssessment() {
    AtomicReference<Boolean> hook = new AtomicReference<>();

    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> {
          run.markReprocessed("/srv/dokumente/a.txt");
          run.markPresent("/srv/dokumente/b.txt");
          run.afterReconciliation(hook::set);
          return ListingOutcome.complete();
        });

    verify(cleanupService)
        .reconcile(
            eq(library),
            eq(DocumentSourceType.FILESYSTEM),
            eq(Set.of("/srv/dokumente/a.txt", "/srv/dokumente/b.txt")),
            eq(Set.of("/srv/dokumente/a.txt")),
            any(),
            eq(fullListingExecutor),
            eq(IndexingRunMode.FULL));
    assertThat(hook.get()).isTrue();
    verify(jobService).recordListingAssessment(jobId, true, List.of());
    verify(jobService).completeJob(eq(jobId), anyInt(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void aFailedReconciliationIsReportedToTheHookAndNeverFailsTheRun() {
    AtomicReference<Boolean> hook = new AtomicReference<>();
    doThrow(new IllegalStateException("Datenbank nicht erreichbar"))
        .when(cleanupService)
        .reconcile(any(), any(), any(), any(), any(), any(), any());

    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> {
          run.afterReconciliation(hook::set);
          return ListingOutcome.complete();
        });

    assertThat(hook.get()).isFalse();
    verify(jobService).recordListingAssessment(jobId, true, List.of());
    verify(jobService).completeJob(eq(jobId), anyInt(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void anIncompleteListingReconcilesNothingAndRecordsTheUnreadableContainers() {
    AtomicReference<Boolean> hook = new AtomicReference<>();

    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> {
          run.afterReconciliation(hook::set);
          return ListingOutcome.incomplete(List.of("SEC"));
        });

    verify(cleanupService, never()).reconcile(any(), any(), any(), any(), any(), any(), any());
    assertThat(hook.get()).isNull();
    verify(jobService).recordListingAssessment(jobId, false, List.of("SEC"));
    verify(jobService).recordRunMetrics(jobId, new IndexingRunCost(0, 0, 0L, 0, 0, 0, false));
  }

  @Test
  void aTruncatedListingLeavesTheAssessmentStandingAndMarksTheCostIncomplete() {
    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> ListingOutcome.truncated());

    verify(cleanupService, never()).reconcile(any(), any(), any(), any(), any(), any(), any());
    verify(jobService, never()).recordListingAssessment(any(), anyBoolean(), any());
    verify(jobService).recordRunMetrics(jobId, new IndexingRunCost(0, 0, 0L, 0, 0, 0, true));
    verify(jobService).completeJob(eq(jobId), anyInt(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void aRunModeThatKeepsOnAbsenceNeverReconcilesNorAssessesWhateverTheBodyReports() {
    template.run(
        jobId,
        library,
        IndexingRunMode.INCREMENTAL,
        windowExecutor,
        run -> ListingOutcome.complete());
    template.run(
        jobId,
        library,
        IndexingRunMode.INCREMENTAL,
        windowExecutor,
        run -> ListingOutcome.partial());

    verify(cleanupService, never()).reconcile(any(), any(), any(), any(), any(), any(), any());
    verify(jobService, never()).recordListingAssessment(any(), anyBoolean(), any());
    verify(jobService, org.mockito.Mockito.times(2))
        .completeJob(eq(jobId), anyInt(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void aPartialListingFromAFullyListingRunModeIsAContractViolationThatFailsTheRun() {
    template.run(
        jobId, library, IndexingRunMode.FULL, fullListingExecutor, run -> ListingOutcome.partial());

    verify(cleanupService, never()).reconcile(any(), any(), any(), any(), any(), any(), any());
    verify(jobService).failJob(eq(jobId), any());
  }

  // --- robustness --------------------------------------------------------------------------

  @Test
  void aFailedCostWriteNeverKeepsTheJobFromEnding() {
    doThrow(new IllegalStateException("Datenbank nicht erreichbar"))
        .when(jobService)
        .recordRunMetrics(any(), any());

    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> ListingOutcome.complete());

    verify(jobService).completeJob(eq(jobId), anyInt(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void theProtocolOverflowIsPersistedBeforeTheJobEnds() {
    template.run(
        jobId,
        library,
        IndexingRunMode.FULL,
        fullListingExecutor,
        run -> {
          for (int i = 0; i < 501; i++) {
            run.events().record(IndexingEventCategory.ERROR, "Verarbeitung fehlgeschlagen", "f");
          }
          return ListingOutcome.complete();
        });

    InOrder order = inOrder(jobService);
    order.verify(jobService).recordEventsTruncated(jobId, 1);
    order.verify(jobService).completeJob(eq(jobId), anyInt(), anyInt(), anyInt(), anyInt());
  }
}
