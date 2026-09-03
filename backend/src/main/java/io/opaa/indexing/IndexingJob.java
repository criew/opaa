package io.opaa.indexing;

import io.opaa.api.types.IndexingRunMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "indexing_jobs")
public class IndexingJob {

  @Id private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private JobStatus status = JobStatus.RUNNING;

  @Column(name = "documents_processed")
  private int documentsProcessed;

  @Column(name = "documents_failed")
  private int documentsFailed;

  @Column(name = "documents_total")
  private int documentsTotal;

  @Column(name = "documents_skipped")
  private int documentsSkipped;

  /**
   * The true count of documents indexed by this run - equals {@code documentsProcessed} for
   * FILESYSTEM/HTTP_DIRECTORY runs (one processed file is exactly one document), but exceeds it for
   * an RSS_FEED run whose entries carry attachments: every attachment indexed for an entry adds to
   * this count without adding another processed entry. {@link IndexingRunProgress#recordProcessed}
   * increments this alongside {@code documentsProcessed}; {@link
   * IndexingRunProgress#recordDocumentIndexed} increments only this one.
   */
  @Column(name = "documents_indexed_total")
  private int documentsIndexedTotal;

  @Column(name = "started_at", nullable = false, updatable = false)
  private Instant startedAt;

  /**
   * Heartbeat for the stale-run sweep: {@code IndexingJobService#updateProgress} touches this on
   * every file/entry an active run processes, so {@code IndexingJobRepository#failStaleRunningJobs}
   * can tell a merely long-running job apart from one that has genuinely stopped making progress -
   * {@link #startedAt} alone cannot make that distinction. Initialized to {@link #startedAt} so a
   * run that fails before its first progress report is still comparable against the sweep's cutoff.
   */
  @Column(name = "last_progress_at", nullable = false)
  private Instant lastProgressAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "error_message", columnDefinition = "text")
  private String errorMessage;

  /**
   * The knowledge library this run writes into - set once at {@link
   * io.opaa.indexing.IndexingJobService#startJob}, so a completed or failed run stays traceable to
   * its target after the fact. Nullable because a target library can later be deleted ({@code ON
   * DELETE SET NULL}).
   */
  @Column(name = "library_id")
  private UUID libraryId;

  /**
   * How many further {@link IndexingRunEvent}s this run recorded beyond {@link
   * IndexingRunEventRecorder#MAX_EVENTS_PER_RUN}, without persisting them - 0 when every event fit
   * under the cap. Set once, at the end of a run, by {@code
   * IndexingJobService#recordEventsTruncated}; the UI renders it as "… und N weitere" after the
   * (necessarily capped) event list.
   */
  @Column(name = "events_truncated_count")
  private int eventsTruncatedCount;

  /**
   * The organization this run belongs to - set once at {@link
   * io.opaa.indexing.IndexingJobService#startJob}, mirroring {@link #libraryId} but never left
   * unset going forward: unlike {@code libraryId} (nullable, {@code ON DELETE SET NULL}), {@code
   * organization_id} is {@code NOT NULL} at the database level - a run's organization must stay
   * reconstructable even after its target library is deleted.
   */
  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  /**
   * Who started this run - {@link JobTriggerSource#MANUAL} unless {@link
   * io.opaa.indexing.IndexingJobService#startJob(java.util.UUID, java.util.UUID, JobTriggerSource,
   * io.opaa.api.types.IndexingRunMode)} was called with {@link JobTriggerSource#SCHEDULED}. {@code
   * KnowledgeLibraryService} uses this to compute {@code LibraryResponse.lastScheduledRunsFailed}
   * without conflating a manual retry with the scheduled runs it retried after.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "triggered_by", nullable = false, length = 20)
  private JobTriggerSource triggeredBy = JobTriggerSource.MANUAL;

  /**
   * ADR-0023, Entscheidung 4: whether this run listed its source completely (FULL) or only picked
   * up changes (INCREMENTAL) - the attribute the deletion semantics hang on, visible in the run
   * protocol and the API. Chosen by DocumentIndexingService from the executor's declaration.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "run_mode", nullable = false, length = 20)
  private IndexingRunMode runMode = IndexingRunMode.FULL;

  /**
   * #1141: a COMPLETED run that stopped in an orderly way before covering everything (its request
   * budget ran out) and is continued by the next run - distinct from FAILED, which means the run
   * itself broke. Never {@code true} on a FAILED or RUNNING row.
   */
  @Column(name = "incomplete", nullable = false)
  private boolean incomplete;

  /** #1141: the run's own cost figures; {@code null} until the executor records them at the end. */
  @Column(name = "requests_sent")
  private Integer requestsSent;

  @Column(name = "throttle_count")
  private Integer throttleCount;

  @Column(name = "throttle_wait_millis")
  private Long throttleWaitMillis;

  @Column(name = "attachments_processed")
  private Integer attachmentsProcessed;

  @Column(name = "attachments_skipped")
  private Integer attachmentsSkipped;

  @Column(name = "attachments_failed")
  private Integer attachmentsFailed;

  protected IndexingJob() {}

  public IndexingJob(JobStatus status) {
    this.id = UUID.randomUUID();
    this.status = status;
    this.startedAt = Instant.now();
    this.lastProgressAt = this.startedAt;
  }

  public UUID getId() {
    return id;
  }

  public JobStatus getStatus() {
    return status;
  }

  public void setStatus(JobStatus status) {
    this.status = status;
  }

  public int getDocumentsProcessed() {
    return documentsProcessed;
  }

  public void setDocumentsProcessed(int documentsProcessed) {
    this.documentsProcessed = documentsProcessed;
  }

  public int getDocumentsFailed() {
    return documentsFailed;
  }

  public void setDocumentsFailed(int documentsFailed) {
    this.documentsFailed = documentsFailed;
  }

  public int getDocumentsTotal() {
    return documentsTotal;
  }

  public void setDocumentsTotal(int documentsTotal) {
    this.documentsTotal = documentsTotal;
  }

  public int getDocumentsSkipped() {
    return documentsSkipped;
  }

  public void setDocumentsSkipped(int documentsSkipped) {
    this.documentsSkipped = documentsSkipped;
  }

  public int getDocumentsIndexedTotal() {
    return documentsIndexedTotal;
  }

  public void setDocumentsIndexedTotal(int documentsIndexedTotal) {
    this.documentsIndexedTotal = documentsIndexedTotal;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getLastProgressAt() {
    return lastProgressAt;
  }

  public void setLastProgressAt(Instant lastProgressAt) {
    this.lastProgressAt = lastProgressAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(Instant completedAt) {
    this.completedAt = completedAt;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public UUID getLibraryId() {
    return libraryId;
  }

  public void setLibraryId(UUID libraryId) {
    this.libraryId = libraryId;
  }

  public int getEventsTruncatedCount() {
    return eventsTruncatedCount;
  }

  public void setEventsTruncatedCount(int eventsTruncatedCount) {
    this.eventsTruncatedCount = eventsTruncatedCount;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public void setOrganizationId(UUID organizationId) {
    this.organizationId = organizationId;
  }

  public JobTriggerSource getTriggeredBy() {
    return triggeredBy;
  }

  public IndexingRunMode getRunMode() {
    return runMode;
  }

  public void setRunMode(IndexingRunMode runMode) {
    this.runMode = runMode;
  }

  public boolean isIncomplete() {
    return incomplete;
  }

  /** The metrics the run recorded, or {@code null} when it recorded none (#1141). */
  public IndexingRunMetrics getMetrics() {
    if (requestsSent == null) {
      return null;
    }
    return new IndexingRunMetrics(
        requestsSent,
        throttleCount == null ? 0 : throttleCount,
        throttleWaitMillis == null ? 0L : throttleWaitMillis,
        attachmentsProcessed == null ? 0 : attachmentsProcessed,
        attachmentsSkipped == null ? 0 : attachmentsSkipped,
        attachmentsFailed == null ? 0 : attachmentsFailed,
        incomplete);
  }

  public void applyMetrics(IndexingRunMetrics metrics) {
    this.requestsSent = metrics.requestsSent();
    this.throttleCount = metrics.throttleCount();
    this.throttleWaitMillis = metrics.throttleWaitMillis();
    this.attachmentsProcessed = metrics.attachmentsProcessed();
    this.attachmentsSkipped = metrics.attachmentsSkipped();
    this.attachmentsFailed = metrics.attachmentsFailed();
    this.incomplete = metrics.incomplete();
  }

  public void setTriggeredBy(JobTriggerSource triggeredBy) {
    this.triggeredBy = triggeredBy;
  }
}
