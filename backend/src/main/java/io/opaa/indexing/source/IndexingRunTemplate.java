package io.opaa.indexing.source;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.document.DocumentRepository;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingJobService;
import io.opaa.indexing.job.IndexingRunEventRecorder;
import io.opaa.indexing.job.IndexingRunEventRepository;
import io.opaa.indexing.job.IndexingRunProgress;
import io.opaa.indexing.maintenance.StaleDocumentCleanupService;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.sourceaccess.SourceRequestMeter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The frame every connector run shares: it starts the job's progress and protocol, rejects a run
 * mode the executor does not declare, runs the connector's body, reconciles by absence when the
 * body listed the source completely and the mode allows it, notes throttling and a spent budget
 * from the run's request meter, persists the listing assessment and the run's cost, and ends the
 * job exactly once - completed, or failed with a German message.
 *
 * <p>A body ends its run early by throwing {@link IndexingRunFailedException} with the message the
 * job should carry. A {@link RequestBudgetExhaustedException} ends the run as truncated - noted
 * with the body's {@link IndexingRun#budgetContinuation continuation}, never failed. An {@link
 * InterruptedException} fails the run as interrupted, a {@link DataIntegrityViolationException} as
 * "library deleted during the run" (the only way a foreign key to the library can break mid-run),
 * any other exception with its own message.
 */
public class IndexingRunTemplate {

  private static final Logger log = LoggerFactory.getLogger(IndexingRunTemplate.class);

  static final String LIBRARY_DELETED_MESSAGE = "Die Bibliothek wurde während des Laufs gelöscht.";
  public static final String INTERRUPTED_MESSAGE = "Lauf unterbrochen";

  /**
   * A connector's run body: enumerate the source, hand every item to processing through {@link
   * IndexingRun}, and say how completely the source was listed.
   */
  @FunctionalInterface
  public interface RunBody {
    ListingOutcome run(IndexingRun run) throws Exception;
  }

  private final IndexingJobService indexingJobService;
  private final IndexingRunEventRepository eventRepository;
  private final StaleDocumentCleanupService staleDocumentCleanupService;
  private final DocumentRepository documentRepository;
  private final LibraryStorageQuotaService storageQuotaService;

  public IndexingRunTemplate(
      IndexingJobService indexingJobService,
      IndexingRunEventRepository eventRepository,
      StaleDocumentCleanupService staleDocumentCleanupService,
      DocumentRepository documentRepository,
      LibraryStorageQuotaService storageQuotaService) {
    this.indexingJobService = indexingJobService;
    this.eventRepository = eventRepository;
    this.staleDocumentCleanupService = staleDocumentCleanupService;
    this.documentRepository = documentRepository;
    this.storageQuotaService = storageQuotaService;
  }

  /** Runs {@code body} for {@code jobId} inside the frame described on this class. */
  public void run(
      UUID jobId,
      KnowledgeLibrary library,
      IndexingRunMode runMode,
      SourceIndexingExecutor executor,
      RunBody body) {
    var progress = new IndexingRunProgress(indexingJobService, jobId);
    var events = new IndexingRunEventRecorder(eventRepository, indexingJobService, jobId);
    VanishedDocumentPolicy policy = executor.runModes().get(runMode);
    if (policy == null) {
      progress.fail("Betriebsart " + runMode + " wird für diesen Quellentyp nicht unterstützt");
      return;
    }
    var run =
        new IndexingRun(
            jobId,
            library,
            runMode,
            executor.sourceType().documentSourceType(),
            progress,
            events,
            documentRepository,
            storageQuotaService);
    boolean failed = false;
    String failure = null;
    boolean incomplete = false;
    boolean interrupted = false;
    try {
      ListingOutcome listing = Objects.requireNonNull(body.run(run), "listing outcome");
      incomplete = listing instanceof ListingOutcome.Truncated;
      if (policy == VanishedDocumentPolicy.REMOVE_ON_ABSENCE) {
        finishCompleteListing(run, executor, listing);
      }
    } catch (RequestBudgetExhaustedException e) {
      log.info(
          "Indexing run {} for library {} ended at its bound: {}",
          jobId,
          library.getId(),
          e.getMessage());
      recordBudgetExhausted(run, e);
      incomplete = true;
    } catch (IndexingRunFailedException e) {
      log.warn("Indexing run {} for library {} failed: {}", jobId, library.getId(), e.getMessage());
      failed = true;
      failure = e.getMessage();
    } catch (InterruptedException e) {
      log.warn("Indexing run {} for library {} interrupted", jobId, library.getId());
      // a body may rethrow with the flag already set; it is cleared for the writes below and
      // restored in the finally
      Thread.interrupted();
      failed = true;
      failure = INTERRUPTED_MESSAGE;
      interrupted = true;
    } catch (DataIntegrityViolationException e) {
      log.error(
          "Indexing run {} failed - target library {} no longer exists", jobId, library.getId(), e);
      failed = true;
      failure = LIBRARY_DELETED_MESSAGE;
    } catch (Exception e) {
      log.error("Indexing run {} for library {} failed unexpectedly", jobId, library.getId(), e);
      failed = true;
      failure =
          e.getMessage() != null
              ? e.getMessage()
              : "Unerwarteter Fehler (" + e.getClass().getSimpleName() + ")";
    }
    reportThrottling(run);
    recordCost(run, !failed && incomplete);
    log.info(
        "Indexing run {} ({}) for library {}: {} processed, {} failed, {} skipped, attachments"
            + " {}/{}/{} (processed/skipped/failed), incomplete={}, failed={}, failure={}",
        jobId,
        run.sourceType(),
        library.getId(),
        progress.processedCount(),
        progress.failedCount(),
        progress.skippedCount(),
        progress.attachmentsProcessed(),
        progress.attachmentsSkipped(),
        progress.attachmentsFailed(),
        incomplete,
        failed,
        failure);
    // The interrupt flag is restored only after the job row is written: a pending interrupt makes
    // the connection acquisition for that write fail and would leave the job RUNNING forever.
    try {
      events.finalizeRun();
      if (failed) {
        progress.fail(failure);
      } else {
        progress.complete();
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * What a fully listing run mode does with its listing: a complete one reconciles and is assessed
   * complete, an incomplete one is assessed as such and reconciles nothing, a truncated one leaves
   * the previous assessment standing. A partial listing is a contract violation of the executor.
   */
  private void finishCompleteListing(
      IndexingRun run, SourceIndexingExecutor executor, ListingOutcome listing) {
    switch (listing) {
      case ListingOutcome.Complete complete -> {
        run.reconciliationFinished(reconcile(run, executor));
        indexingJobService.recordListingAssessment(run.jobId(), true, List.of());
      }
      case ListingOutcome.Incomplete incomplete ->
          indexingJobService.recordListingAssessment(
              run.jobId(), false, incomplete.unlistedScopeKeys());
      case ListingOutcome.Truncated truncated -> {}
      case ListingOutcome.Partial partial ->
          throw new IllegalStateException(
              executor.sourceType()
                  + " declares "
                  + run.runMode()
                  + " as REMOVE_ON_ABSENCE but reported a partial listing");
    }
  }

  /**
   * Removes what the run did not meet - a failure here is logged and reported to the body's hook,
   * never fails the run: what was indexed stays indexed, the next complete run reconciles again.
   */
  private boolean reconcile(IndexingRun run, SourceIndexingExecutor executor) {
    try {
      staleDocumentCleanupService.reconcile(
          run.library(),
          run.sourceType(),
          run.currentPaths(),
          run.reprocessedPaths(),
          run.events(),
          executor,
          run.runMode());
      return true;
    } catch (Exception e) {
      log.warn(
          "Failed to clean up vanished {} documents for library {}",
          run.sourceType(),
          run.library().getId(),
          e);
      return false;
    }
  }

  /**
   * One {@code BUDGET_EXHAUSTED} note naming where the next run continues, and - for a run that
   * stored nothing new, so the chain of resumed runs has stalled - the body's advice as an {@code
   * ERROR}; nothing when the body registered none.
   */
  private static void recordBudgetExhausted(IndexingRun run, RequestBudgetExhaustedException e) {
    run.events()
        .recordRunNote(
            IndexingEventCategory.BUDGET_EXHAUSTED,
            e.getMessage() + "; " + run.budgetContinuation());
    String advice = run.budgetStallAdvice();
    if (advice != null
        && run.progress().processedCount() == 0
        && run.progress().attachmentsProcessed() == 0) {
      run.events().recordRunNote(IndexingEventCategory.ERROR, e.insufficiencyNote(advice));
    }
  }

  /** One {@code RATE_LIMITED} note per run that was throttled at all, from the run's meter. */
  private static void reportThrottling(IndexingRun run) {
    SourceRequestMeter meter = run.requestMeter();
    if (meter.throttles() == 0) {
      return;
    }
    run.events()
        .recordRunNote(
            IndexingEventCategory.RATE_LIMITED,
            "Die Quelle hat den Lauf "
                + meter.throttles()
                + "-mal gedrosselt (HTTP 429/503); der Lauf hat insgesamt "
                + meter.throttledTime().toSeconds()
                + " Sekunden gewartet statt abzubrechen");
  }

  /** A cost write must never keep the job from ending - it is logged and the run goes on. */
  private void recordCost(IndexingRun run, boolean incomplete) {
    try {
      indexingJobService.recordRunMetrics(run.jobId(), run.cost(incomplete));
    } catch (Exception e) {
      log.warn("Failed to record run metrics for job {}, continuing the run", run.jobId(), e);
    }
  }
}
