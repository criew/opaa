package io.opaa.indexing;

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
   * io.opaa.indexing.IndexingJobService#startJob(java.util.UUID, java.util.UUID, JobTriggerSource)}
   * was called with {@link JobTriggerSource#SCHEDULED}. {@code KnowledgeLibraryService} uses this
   * to compute {@code LibraryResponse.lastScheduledRunsFailed} without conflating a manual retry
   * with the scheduled runs it retried after.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "triggered_by", nullable = false, length = 20)
  private JobTriggerSource triggeredBy = JobTriggerSource.MANUAL;

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

  public void setTriggeredBy(JobTriggerSource triggeredBy) {
    this.triggeredBy = triggeredBy;
  }
}
