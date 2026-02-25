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

  @Column(name = "started_at", nullable = false, insertable = false, updatable = false)
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "error_message", columnDefinition = "text")
  private String errorMessage;

  protected IndexingJob() {}

  public IndexingJob(JobStatus status) {
    this.id = UUID.randomUUID();
    this.status = status;
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
}
