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

  @Column(name = "started_at", nullable = false, updatable = false)
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "error_message", columnDefinition = "text")
  private String errorMessage;

  /**
   * The knowledge library this run writes into (#419) - set once at {@link
   * io.opaa.indexing.IndexingJobService#startJob}, so a completed or failed run stays traceable to
   * its target after the fact. Nullable because runs started before migration 019 added this column
   * have no recorded target; a new run always sets it, {@code libraryId} being mandatory on every
   * trigger since #419.
   */
  @Column(name = "library_id")
  private UUID libraryId;

  protected IndexingJob() {}

  public IndexingJob(JobStatus status) {
    this.id = UUID.randomUUID();
    this.status = status;
    this.startedAt = Instant.now();
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

  public Instant getStartedAt() {
    return startedAt;
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
}
