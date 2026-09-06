package io.opaa.indexing.source;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingOutcomes;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.IndexingEventCategory;
import io.opaa.indexing.IndexingRunCost;
import io.opaa.indexing.IndexingRunEventRecorder;
import io.opaa.indexing.IndexingRunProgress;
import io.opaa.indexing.SourceDocumentContext;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryStorageQuotaService;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything one connector run shares across its items, handed to the run body by {@link
 * IndexingRunTemplate}: the job's progress and protocol, the result mapping, the change check, the
 * reconciliation set and the request cost. A connector body only adds what its source needs.
 *
 * <p>The reconciliation set carries the {@code file_path} of every item and attachment present this
 * run ({@link #markPresent}) and the subset whose own attachments were freshly enumerated ({@link
 * #markReprocessed}, ADR-0022, Entscheidung 3); the frame folds the preserved attachments of every
 * other present parent in from the database before it removes what vanished.
 */
public final class IndexingRun {

  private static final Logger log = LoggerFactory.getLogger(IndexingRun.class);

  /**
   * Runs once the frame has attempted the reconciliation; {@code reconciled} is false if it threw.
   */
  @FunctionalInterface
  public interface ReconciliationHook {
    void afterReconciliation(boolean reconciled);
  }

  private final UUID jobId;
  private final KnowledgeLibrary library;
  private final IndexingRunMode runMode;
  private final DocumentSourceType sourceType;
  private final IndexingRunProgress progress;
  private final IndexingRunEventRecorder events;
  private final DocumentRepository documentRepository;
  private final LibraryStorageQuotaService storageQuotaService;
  private final Set<String> currentPaths = new HashSet<>();
  private final Set<String> reprocessedPaths = new HashSet<>();
  private int requestsSent;
  private int throttleCount;
  private long throttleWaitMillis;
  private ReconciliationHook reconciliationHook = reconciled -> {};

  public IndexingRun(
      UUID jobId,
      KnowledgeLibrary library,
      IndexingRunMode runMode,
      DocumentSourceType sourceType,
      IndexingRunProgress progress,
      IndexingRunEventRecorder events,
      DocumentRepository documentRepository,
      LibraryStorageQuotaService storageQuotaService) {
    this.jobId = jobId;
    this.library = library;
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

  public KnowledgeLibrary library() {
    return library;
  }

  public IndexingRunMode runMode() {
    return runMode;
  }

  public DocumentSourceType sourceType() {
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
   * remoteVersion} and is indexed - see {@link io.opaa.indexing.Document#isUnchangedAt}. Scoped to
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
  public boolean recordOutcome(FileProcessingResult result, String reference) {
    return progress.recordOutcome(
        result, reference, events, () -> storageQuotaService.quotaExceededMessage(library.getId()));
  }

  /** An item whose processing threw: logged, an {@code ERROR} entry, counted as failed. */
  public void recordFailure(String reference, Throwable failure) {
    log.error("Failed to process {} ({})", reference, sourceType, failure);
    events.record(IndexingEventCategory.ERROR, FileProcessingOutcomes.FAILED_MESSAGE, reference);
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

  /** What the run's source meter counted; a source without one leaves the zeros. */
  public void recordRequestCost(int requestsSent, int throttleCount, long throttleWaitMillis) {
    this.requestsSent = requestsSent;
    this.throttleCount = throttleCount;
    this.throttleWaitMillis = throttleWaitMillis;
  }

  IndexingRunCost cost(boolean incomplete) {
    return new IndexingRunCost(
        requestsSent,
        throttleCount,
        throttleWaitMillis,
        progress.attachmentsProcessed(),
        progress.attachmentsSkipped(),
        progress.attachmentsFailed(),
        incomplete);
  }
}
