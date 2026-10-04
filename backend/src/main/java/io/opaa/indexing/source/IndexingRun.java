package io.opaa.indexing.source;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.document.DocumentIngestOutcomes;
import io.opaa.indexing.document.DocumentIngestResult;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingRunCost;
import io.opaa.indexing.job.IndexingRunEventRecorder;
import io.opaa.indexing.job.RequestBudgetExhaustedException;
import io.opaa.indexing.job.RunEndingFailures;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryStorageQuotaService;
import io.opaa.knowledge.SourceDocumentContext;
import io.opaa.knowledge.SourceType;
import io.opaa.sourceaccess.SourceRequestMeter;
import java.time.Clock;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything one connector run shares across its items, handed to the run body by {@link
 * IndexingRunTemplate}: the job's progress and protocol, the result mapping, the change check, the
 * reconciliation set and the request cost. A connector body only adds what its source needs.
 *
 * <p>A run's own bound - a spent request budget or an exhausted wait on throttled answers - ends
 * the body with a {@link RequestBudgetExhaustedException}; the frame notes it with the {@link
 * #budgetContinuation continuation} the body registered and ends the run as truncated.
 *
 * <p>The reconciliation set carries the {@code file_path} of every item and attachment present this
 * run ({@link #markPresent}) and the subset whose own attachments were freshly enumerated ({@link
 * #markReprocessed}, ADR-0022, Entscheidung 3); the frame folds the preserved attachments of every
 * other present parent in from the database before it removes what vanished.
 */
public final class IndexingRun {

  private static final Logger log = LoggerFactory.getLogger(IndexingRun.class);

  static final String DEFAULT_BUDGET_CONTINUATION = "der nächste Lauf setzt fort";

  /**
   * Runs once the frame has attempted the reconciliation; {@code reconciled} is false if it threw.
   */
  @FunctionalInterface
  public interface ReconciliationHook {
    void afterReconciliation(boolean reconciled);
  }

  private final UUID jobId;
  private final KnowledgeLibrary library;
  private final SourceSettings settings;
  private final RunCredentials credentials;
  private final IndexingRunMode runMode;
  private final SourceType sourceType;
  private final IndexingRunProgress progress;
  private final IndexingRunEventRecorder events;
  private final DocumentRepository documentRepository;
  private final LibraryStorageQuotaService storageQuotaService;
  private final Set<String> currentPaths = new HashSet<>();
  private final Set<String> reprocessedPaths = new HashSet<>();
  private SourceRequestMeter requestMeter = new SourceRequestMeter();
  private Supplier<String> budgetContinuation = () -> DEFAULT_BUDGET_CONTINUATION;
  private String budgetStallAdvice;
  private ReconciliationHook reconciliationHook = reconciled -> {};

  public IndexingRun(
      UUID jobId,
      KnowledgeLibrary library,
      SourceSettings settings,
      Supplier<Secret> credentials,
      IndexingRunMode runMode,
      SourceType sourceType,
      IndexingRunProgress progress,
      IndexingRunEventRecorder events,
      DocumentRepository documentRepository,
      LibraryStorageQuotaService storageQuotaService) {
    this(
        jobId,
        library,
        settings,
        credentials,
        runMode,
        sourceType,
        progress,
        events,
        documentRepository,
        storageQuotaService,
        rejected -> credentials.get(),
        Clock.systemUTC());
  }

  IndexingRun(
      UUID jobId,
      KnowledgeLibrary library,
      SourceSettings settings,
      Supplier<Secret> credentials,
      IndexingRunMode runMode,
      SourceType sourceType,
      IndexingRunProgress progress,
      IndexingRunEventRecorder events,
      DocumentRepository documentRepository,
      LibraryStorageQuotaService storageQuotaService,
      UnaryOperator<Secret> afterRejection,
      Clock clock) {
    this.jobId = jobId;
    this.library = library;
    this.settings = settings.withoutCredentials();
    this.credentials =
        new RunCredentials(credentials, afterRejection, RunCredentials.VALIDITY, clock);
    this.runMode = runMode;
    this.sourceType = sourceType;
    this.progress = progress;
    this.events = events;
    this.documentRepository = documentRepository;
    this.storageQuotaService = storageQuotaService;
  }

  public UUID jobId() {
    return jobId;
  }

  /**
   * The library the run writes into - its identity and its inventory. Its source configuration
   * comes from {@link #settings()} and {@link #credentials()}.
   */
  public KnowledgeLibrary library() {
    return library;
  }

  /**
   * Target, proxy, TLS switch and connector settings as the core resolved them at the start of the
   * run; never the secret, which {@link #credentials()} answers.
   */
  public SourceSettings settings() {
    return settings;
  }

  /**
   * The secret as a connector asks for it before every request or sign-in: renewed by the core and
   * refused once the source is blocked, at most {@link RunCredentials#VALIDITY} old.
   */
  public RunCredentials credentials() {
    return credentials;
  }

  public IndexingRunMode runMode() {
    return runMode;
  }

  public SourceType sourceType() {
    return sourceType;
  }

  public IndexingRunProgress progress() {
    return progress;
  }

  public IndexingRunEventRecorder events() {
    return events;
  }

  /**
   * Whether the document at {@code filePath} in this run's library already holds {@code
   * remoteVersion} and is indexed - see {@link io.opaa.knowledge.Document#isUnchangedAt}. Scoped to
   * the library, so the same path in another library never matches.
   */
  public boolean isUnchanged(String filePath, String remoteVersion) {
    return documentRepository
        .findByLibraryIdAndFilePath(library.getId(), filePath)
        .filter(existing -> existing.isUnchangedAt(remoteVersion))
        .isPresent();
  }

  /**
   * Maps one item's result onto the counters and the protocol ({@link
   * IndexingRunProgress#recordOutcome}); {@code reference} names the item in the protocol.
   *
   * @return whether the item was processed
   */
  public boolean recordOutcome(DocumentIngestResult result, String reference) {
    return progress.recordOutcome(
        result, reference, events, () -> storageQuotaService.quotaExceededMessage(library.getId()));
  }

  /** An item whose processing threw: logged, an {@code ERROR} entry, counted as failed. */
  public void recordFailure(String reference, Throwable failure) {
    log.error("Failed to process {} ({})", reference, sourceType, failure);
    events.record(IndexingEventCategory.ERROR, DocumentIngestOutcomes.FAILED_MESSAGE, reference);
    progress.recordFailed();
  }

  /** An item or attachment the run met at the source - present, whatever its outcome. */
  public void markPresent(String path) {
    currentPaths.add(path);
  }

  /** An item whose content was re-parsed, so its attachment set was freshly enumerated. */
  public void markReprocessed(String path) {
    currentPaths.add(path);
    reprocessedPaths.add(path);
  }

  /** Withdraws a path from the reconciliation set - the source itself reported it gone. */
  public void markAbsent(String path) {
    currentPaths.remove(path);
    reprocessedPaths.remove(path);
  }

  public Set<String> currentPaths() {
    return Collections.unmodifiableSet(currentPaths);
  }

  public Set<String> reprocessedPaths() {
    return Collections.unmodifiableSet(reprocessedPaths);
  }

  /** The {@link ReconcilingAttachmentAccess} for a parent without a source context. */
  public ReconcilingAttachmentAccess attachmentAccess() {
    return attachmentAccess(SourceDocumentContext.NONE);
  }

  /** The {@link ReconcilingAttachmentAccess} for a parent that sits at {@code context}. */
  public ReconcilingAttachmentAccess attachmentAccess(SourceDocumentContext context) {
    return new ReconcilingAttachmentAccess(this, context);
  }

  /**
   * Registers what runs once the frame has attempted the reconciliation - a connector's own "full
   * listing done" state or folder pruning. Never called when no reconciliation was attempted (an
   * incomplete or truncated listing, a run mode that keeps on absence). Replaces any earlier hook.
   */
  public void afterReconciliation(ReconciliationHook hook) {
    this.reconciliationHook = hook;
  }

  void reconciliationFinished(boolean reconciled) {
    reconciliationHook.afterReconciliation(reconciled);
  }

  /**
   * The meter the run's source counted on - read by the frame for the run's cost and the throttle
   * note once the body has ended; a source without one leaves the zeros.
   */
  public void recordRequestCost(SourceRequestMeter meter) {
    this.requestMeter = meter;
  }

  SourceRequestMeter requestMeter() {
    return requestMeter;
  }

  /**
   * Where the next run continues once this run's request budget is spent - the tail of the {@code
   * BUDGET_EXHAUSTED} note after "erschöpft; ", read the moment the budget runs out so it can name
   * what the run has met so far. Replaces any earlier continuation; a body updates it as it moves
   * through its source.
   */
  public void budgetContinuation(Supplier<String> continuation) {
    this.budgetContinuation = continuation;
  }

  /**
   * What to change when a run reached its budget without storing a single item or attachment - the
   * chain of resumed runs has stalled, and the frame says so in an {@code ERROR} note. {@code null}
   * (the default) writes no such note.
   */
  public void budgetStallAdvice(String advice) {
    this.budgetStallAdvice = advice;
  }

  String budgetContinuation() {
    return budgetContinuation.get();
  }

  String budgetStallAdvice() {
    return budgetStallAdvice;
  }

  /**
   * Lets what must end the run pass a connector's item catch. The connectors' entry point; the rule
   * itself is {@link RunEndingFailures#rethrow}, which {@code document} uses below this package.
   */
  public static void rethrowRunEnding(Throwable failure) throws InterruptedException {
    RunEndingFailures.rethrow(failure);
  }

  IndexingRunCost cost(boolean incomplete) {
    return new IndexingRunCost(
        requestMeter.requests(),
        requestMeter.throttles(),
        requestMeter.throttledTime().toMillis(),
        progress.attachmentsProcessed(),
        progress.attachmentsSkipped(),
        progress.attachmentsFailed(),
        incomplete,
        requestMeter.bytesDownloaded());
  }
}
