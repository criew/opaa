package io.opaa.indexing.run;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.credentials.ServiceAccountTokens;
import io.opaa.indexing.credentials.SourceConnectionResolver;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingJobService;
import io.opaa.indexing.job.IndexingRunEventRecorder;
import io.opaa.indexing.job.IndexingRunEventRepository;
import io.opaa.indexing.job.RequestBudgetExhaustedException;
import io.opaa.indexing.job.RunAbandonedException;
import io.opaa.indexing.registry.IndexingSourceExecutorRegistry;
import io.opaa.indexing.spi.IndexingRunFailedException;
import io.opaa.indexing.spi.IndexingRunProgress;
import io.opaa.indexing.spi.ListingOutcome;
import io.opaa.indexing.spi.RunFailureCategory;
import io.opaa.indexing.spi.Secret;
import io.opaa.indexing.spi.SourceConnectionBlockedException;
import io.opaa.indexing.spi.SourceCredentialsRejectedException;
import io.opaa.indexing.spi.SourceIndexingExecutor;
import io.opaa.indexing.spi.SourceSettings;
import io.opaa.indexing.spi.VanishedDocumentPolicy;
import io.opaa.indexing.spi.VanishedDocumentReconciler;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryStorageQuotaService;
import io.opaa.outbound.ProxyRefusal;
import io.opaa.sourceaccess.SourceRequestMeter;
import io.opaa.sourceaccess.UntrustedCertificate;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The frame every connector run shares: it refuses a body not defined by the executor class
 * registered for the library's source type or a job not started for the library, starts the job's
 * progress and protocol, rejects a run mode the executor does not declare, resolves the library's
 * source through the {@link SourceConnectionResolver}, runs the connector's body, reconciles by
 * absence when the body listed the source completely and the mode allows it, notes throttling and a
 * spent budget from the run's request meter, persists the listing assessment and the run's cost,
 * and ends the job exactly once - completed, or failed with a German message.
 *
 * <p>A body ends its run early by throwing {@link IndexingRunFailedException} with the message the
 * job should carry. A {@link RunAbandonedException} - the run no longer holds its job - ends it
 * without any further write, before anything is reconciled. A {@link
 * RequestBudgetExhaustedException} ends the run as truncated - noted with the body's {@link
 * IndexingRun#budgetContinuation continuation}, never failed. A run that rejected an item at a
 * storage quota (its library's or its owner's) goes on to its end and is completed as incomplete
 * with the category {@link RunFailureCategory#QUOTA_EXHAUSTED}. An {@link InterruptedException}
 * fails the run as interrupted, a {@link SourceConnectionBlockedException} from {@link
 * IndexingRun#credentials} with the block's notice, a {@link SourceCredentialsRejectedException}
 * with its message after telling the port, a {@link LibraryErasureRequestedException} once the
 * library is being erased, a {@link DataIntegrityViolationException} as "library deleted during the
 * run" (the only way a foreign key to the library can break mid-run), any other exception with its
 * own message.
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
  private final VanishedDocumentReconciler staleDocumentCleanupService;
  private final DocumentRepository documentRepository;
  private final LibraryStorageQuotaService storageQuotaService;
  private final SourceConnectionResolver connectionResolver;
  private final Clock clock;
  private final ServiceAccountTokens tokens;
  private final KnowledgeLibraryRepository libraries;
  private final SyncStateBasis settingsBasis;
  private final Supplier<IndexingSourceExecutorRegistry> executors;

  /** {@code clock} measures how long a run reuses its secret. */
  public IndexingRunTemplate(
      IndexingJobService indexingJobService,
      IndexingRunEventRepository eventRepository,
      VanishedDocumentReconciler staleDocumentCleanupService,
      DocumentRepository documentRepository,
      LibraryStorageQuotaService storageQuotaService,
      SourceConnectionResolver connectionResolver,
      Clock clock) {
    this(
        indexingJobService,
        eventRepository,
        staleDocumentCleanupService,
        documentRepository,
        storageQuotaService,
        connectionResolver,
        clock,
        null);
  }

  /**
   * {@code tokens}, when given, drops an access token a source rejected before the port is asked
   * again, so a port that signs through it hands out a new one instead of the cached one.
   */
  public IndexingRunTemplate(
      IndexingJobService indexingJobService,
      IndexingRunEventRepository eventRepository,
      VanishedDocumentReconciler staleDocumentCleanupService,
      DocumentRepository documentRepository,
      LibraryStorageQuotaService storageQuotaService,
      SourceConnectionResolver connectionResolver,
      Clock clock,
      ServiceAccountTokens tokens) {
    this(
        indexingJobService,
        eventRepository,
        staleDocumentCleanupService,
        documentRepository,
        storageQuotaService,
        connectionResolver,
        clock,
        tokens,
        null);
  }

  /**
   * {@code libraries}, when given, is asked with every ask for the secret whether the library is
   * being erased; then the run ends there ({@link LibraryErasureRequestedException}).
   */
  public IndexingRunTemplate(
      IndexingJobService indexingJobService,
      IndexingRunEventRepository eventRepository,
      VanishedDocumentReconciler staleDocumentCleanupService,
      DocumentRepository documentRepository,
      LibraryStorageQuotaService storageQuotaService,
      SourceConnectionResolver connectionResolver,
      Clock clock,
      ServiceAccountTokens tokens,
      KnowledgeLibraryRepository libraries) {
    this(
        indexingJobService,
        eventRepository,
        staleDocumentCleanupService,
        documentRepository,
        storageQuotaService,
        connectionResolver,
        clock,
        tokens,
        libraries,
        SyncStateBasis.WHOLE_SETTINGS,
        null);
  }

  /**
   * {@code settingsBasis} fingerprints the settings a run begins with; a sync state written under
   * another fingerprint is not continued, and a run whose settings change meanwhile removes nothing
   * for being absent. {@code executors}, when given, admits only the bodies and jobs described on
   * this class; a lambda or method reference belongs to the class that defines it.
   */
  public IndexingRunTemplate(
      IndexingJobService indexingJobService,
      IndexingRunEventRepository eventRepository,
      VanishedDocumentReconciler staleDocumentCleanupService,
      DocumentRepository documentRepository,
      LibraryStorageQuotaService storageQuotaService,
      SourceConnectionResolver connectionResolver,
      Clock clock,
      ServiceAccountTokens tokens,
      KnowledgeLibraryRepository libraries,
      SyncStateBasis settingsBasis,
      Supplier<IndexingSourceExecutorRegistry> executors) {
    this.executors = executors;
    this.settingsBasis = settingsBasis;
    this.libraries = libraries;
    this.tokens = tokens;
    this.clock = clock;
    this.indexingJobService = indexingJobService;
    this.eventRepository = eventRepository;
    this.staleDocumentCleanupService = staleDocumentCleanupService;
    this.documentRepository = documentRepository;
    this.storageQuotaService = storageQuotaService;
    this.connectionResolver = connectionResolver;
  }

  /** Runs {@code body} for {@code jobId} inside the frame described on this class. */
  public void run(
      UUID jobId,
      KnowledgeLibrary library,
      IndexingRunMode runMode,
      SourceIndexingExecutor executor,
      RunBody body) {
    requireRegistered(jobId, library, body);
    var progress = new IndexingRunProgress(indexingJobService, jobId);
    var events = new IndexingRunEventRecorder(eventRepository, indexingJobService, jobId);
    VanishedDocumentPolicy policy = executor.runModes().get(runMode);
    if (policy == null) {
      progress.fail("Betriebsart " + runMode + " wird für diesen Quellentyp nicht unterstützt");
      return;
    }
    SourceSettings settings;
    String basis;
    String generation;
    try {
      requireNotErased(library);
      settings = connectionResolver.resolve(library);
      basis = settingsBasis.of(library, settings, connectionResolver.connectedAccount(library));
      generation = connectionResolver.connectionGeneration(library);
    } catch (LibraryErasureRequestedException e) {
      progress.fail(e.getMessage());
      return;
    } catch (SourceConnectionBlockedException e) {
      log.warn(
          "Indexing run {} found the connection of library {} blocked: {}",
          jobId,
          library.getId(),
          e.block().reason());
      progress.fail(e.getMessage(), RunFailureCategory.of(e.block().reason()));
      return;
    } catch (RuntimeException e) {
      log.error(
          "Indexing run {} could not resolve the source of library {}",
          jobId,
          library.getId(),
          library.loggedNames().of(e));
      progress.fail(failureMessage(e));
      return;
    }
    var run =
        new IndexingRun(
            jobId,
            library,
            settings,
            () -> {
              requireNotErased(library);
              return unlessReconnected(
                  library, generation, connectionResolver.currentSecret(library));
            },
            runMode,
            executor.sourceType(),
            progress,
            events,
            staleDocumentCleanupService,
            documentRepository,
            storageQuotaService,
            rejected -> {
              if (tokens != null) {
                tokens.discard(rejected);
              }
              requireNotErased(library);
              return unlessReconnected(
                  library, generation, connectionResolver.secretAfterRejection(library, rejected));
            },
            clock);
    run.bindSettingsBasis(basis, () -> settingsBasis.current(now(library), connectionResolver));
    boolean failed = false;
    String failure = null;
    RunFailureCategory category = null;
    boolean incomplete = false;
    boolean interrupted = false;
    try {
      ListingOutcome listing = Objects.requireNonNull(body.run(run), "listing outcome");
      incomplete = listing instanceof ListingOutcome.Truncated;
      if (policy == VanishedDocumentPolicy.REMOVE_ON_ABSENCE) {
        finishCompleteListing(run, executor, listing);
      }
    } catch (RunAbandonedException e) {
      log.warn(
          "Indexing run {} for library {} ends without further writes: {}",
          jobId,
          library.getId(),
          e.getMessage());
      return;
    } catch (RequestBudgetExhaustedException e) {
      log.info(
          "Indexing run {} for library {} ended at its bound: {}",
          jobId,
          library.getId(),
          library.loggedNames().of(e.getMessage()));
      recordBudgetExhausted(run, e);
      incomplete = true;
    } catch (SourceCredentialsRejectedException e) {
      log.warn(
          "Indexing run {} for library {} ended with category {}: {}",
          jobId,
          library.getId(),
          e.category(),
          library.loggedNames().of(e.getMessage()));
      tellCredentialsRejected(library);
      failed = true;
      failure = e.getMessage();
      category = e.category();
    } catch (IndexingRunFailedException e) {
      log.warn(
          "Indexing run {} for library {} failed: {}",
          jobId,
          library.getId(),
          library.loggedNames().of(e.getMessage()));
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
    } catch (LibraryErasureRequestedException e) {
      log.info("Indexing run {} for library {} ended: erasure requested", jobId, library.getId());
      failed = true;
      failure = e.getMessage();
    } catch (ConnectionReconnectedException e) {
      log.info(
          "Indexing run {} for library {} ended: connection reconnected", jobId, library.getId());
      failed = true;
      failure = e.getMessage();
    } catch (SourceConnectionBlockedException e) {
      log.warn(
          "Indexing run {} for library {} was blocked during the run: {}",
          jobId,
          library.getId(),
          e.block().reason());
      failed = true;
      failure = e.getMessage();
      category = RunFailureCategory.of(e.block().reason());
    } catch (DataIntegrityViolationException e) {
      log.error(
          "Indexing run {} failed - target library {} no longer exists",
          jobId,
          library.getId(),
          library.loggedNames().of(e));
      failed = true;
      failure = LIBRARY_DELETED_MESSAGE;
    } catch (Exception e) {
      log.error(
          "Indexing run {} for library {} failed unexpectedly",
          jobId,
          library.getId(),
          library.loggedNames().of(e));
      failed = true;
      failure = failureMessage(e);
    }
    if (!failed && progress.quotaReached()) {
      incomplete = true;
      category = RunFailureCategory.QUOTA_EXHAUSTED;
    }
    reportThrottling(run);
    recordCost(run, !failed && incomplete);
    log.info(
        "Indexing run {} ({}) for library {}: {} processed, {} failed, {} skipped, attachments"
            + " {}/{}/{} (processed/skipped/failed), incomplete={}, failed={}, category={},"
            + " failure={}",
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
        category,
        library.loggedNames().of(failure));
    // The interrupt flag is restored only after the job row is written: a pending interrupt makes
    // the connection acquisition for that write fail and would leave the job RUNNING forever.
    try {
      events.finalizeRun();
      if (failed && category != null) {
        progress.fail(failure, category);
      } else if (failed) {
        progress.fail(failure);
      } else if (category != null) {
        progress.complete(category);
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
   * The library as stored now - a run holds the one it began with. A library gone meanwhile counts
   * as the one held: its deletion ends the run on its own.
   */
  private KnowledgeLibrary now(KnowledgeLibrary library) {
    return libraries == null ? library : libraries.findById(library.getId()).orElse(library);
  }

  /**
   * {@code jobId} must be a running job the core started for this library; another is refused
   * untouched, it is not this library's to fail. The body must come from the class the core hands a
   * library of this source type to - a proxy of the registered executor counts as the class it
   * wraps; a refused job is failed at once, so it does not hold the library until the recovery.
   */
  private void requireRegistered(UUID jobId, KnowledgeLibrary library, RunBody body) {
    if (executors == null) {
      return;
    }
    if (!indexingJobService.isRunningFor(jobId, library.getId())) {
      throw new IllegalArgumentException(
          "Job " + jobId + " is not a running job of library " + library.getId());
    }
    Class<?> host = body.getClass().getNestHost();
    if (executors
        .get()
        .find(library.getSourceType())
        .filter(registered -> AopUtils.getTargetClass(registered).getNestHost() == host)
        .isEmpty()) {
      indexingJobService.failJob(
          jobId, "Lauf abgelehnt: Er stammt nicht vom Konnektor dieses Quellentyps");
      throw new IllegalArgumentException(
          host.getName()
              + " may not run library "
              + library.getId()
              + " of source type "
              + library.getSourceType());
    }
  }

  private void requireNotErased(KnowledgeLibrary library) {
    if (libraries != null && libraries.isErasureRequested(library.getId())) {
      throw new LibraryErasureRequestedException();
    }
  }

  /**
   * {@code secret}, read before the generation is asked, so a secret of a connection made after the
   * run began never reaches the run.
   */
  private Secret unlessReconnected(KnowledgeLibrary library, String generation, Secret secret) {
    if (!Objects.equals(generation, connectionResolver.connectionGeneration(library))) {
      throw new ConnectionReconnectedException();
    }
    return secret;
  }

  /** A failure to tell the port is logged; it never keeps the run from ending. */
  private void tellCredentialsRejected(KnowledgeLibrary library) {
    try {
      connectionResolver.credentialsRejected(library);
    } catch (RuntimeException e) {
      log.warn(
          "Failed to report the rejected credentials of library {}",
          library.getId(),
          library.loggedNames().of(e));
    }
  }

  private static String failureMessage(Exception e) {
    Optional<String> proxyRefusal = ProxyRefusal.describe(e);
    if (proxyRefusal.isPresent()) {
      return proxyRefusal.get();
    }
    // a connector message that already names the certificate (with its host) stays
    if (UntrustedCertificate.causes(e)
        && (e.getMessage() == null || !e.getMessage().contains(UntrustedCertificate.MESSAGE))) {
      return UntrustedCertificate.MESSAGE;
    }
    return e.getMessage() != null
        ? e.getMessage()
        : "Unerwarteter Fehler (" + e.getClass().getSimpleName() + ")";
  }

  /**
   * What a fully listing run mode does with its listing: a complete one reconciles and is assessed
   * complete, one with unreadable areas reconciles all but their retained documents and is assessed
   * incomplete, an incomplete one is assessed as such and reconciles nothing, a truncated one
   * leaves the previous assessment standing. A partial listing is a contract violation of the
   * executor. A listing whose settings changed while it went reconciles nothing and is assessed
   * incomplete: what it did not meet under the old settings may exist under the new ones.
   */
  private void finishCompleteListing(
      IndexingRun run, SourceIndexingExecutor executor, ListingOutcome listing) {
    // a run given up meanwhile must not remove what the run after it listed
    indexingJobService.requireHeld(run.jobId());
    if ((listing instanceof ListingOutcome.Complete
            || listing instanceof ListingOutcome.CompleteExcept)
        && !run.settingsUnchanged()) {
      log.info(
          "Indexing run {} for library {}: settings changed during the run, nothing reconciled",
          run.jobId(),
          run.library().getId());
      run.events().recordRunNote(IndexingEventCategory.SUMMARY, run.settingsChangeNote());
      indexingJobService.recordListingAssessment(run.jobId(), false, List.of());
      return;
    }
    switch (listing) {
      case ListingOutcome.Complete complete -> {
        run.reconciliationFinished(reconcile(run, executor));
        indexingJobService.recordListingAssessment(run.jobId(), true, List.of());
      }
      case ListingOutcome.CompleteExcept except -> {
        run.reconciliationFinished(retainKnown(run, except.retained()) && reconcile(run, executor));
        indexingJobService.recordListingAssessment(run.jobId(), false, List.of());
        indexingJobService.recordUnreadableScopes(run.jobId(), except.unreadableScopes());
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
   * Marks every known top-level document of the run's source whose key {@code retained} matches
   * present; attachments follow their retained parent in the reconciliation, never their own key,
   * which a crafted attachment name could point anywhere. A run that met nothing itself retains
   * nothing, so its empty bestand still deletes nothing. A failure is logged and returned as {@code
   * false}: without the retained keys nothing may be reconciled, but the run itself does not fail.
   */
  private boolean retainKnown(IndexingRun run, Predicate<String> retained) {
    if (run.currentPaths().isEmpty()) {
      return true;
    }
    try {
      for (Document document :
          documentRepository.findByLibraryIdAndSourceType(
              run.library().getId(), run.sourceType())) {
        if (document.getParentDocumentId() == null && retained.test(document.getFilePath())) {
          run.markPresent(document.getFilePath());
        }
      }
      return true;
    } catch (Exception e) {
      log.warn(
          "Failed to load the retained {} documents of library {}, skipping the reconciliation",
          run.sourceType(),
          run.library().getId(),
          run.library().loggedNames().of(e));
      return false;
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
          run.library().loggedNames().of(e));
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
      log.warn(
          "Failed to record run metrics for job {}, continuing the run",
          run.jobId(),
          run.library().loggedNames().of(e));
    }
  }
}
