package io.opaa.indexing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * A single skipped/rejected item or error an indexing run recorded (#513) - the "why" behind a
 * lower documentsSkipped/documentsFailed count than the number of items the source offered, which
 * before this issue was only visible in the backend log (see the issue's motivating BMF case: 19 of
 * 20 RSS entries skipped by bot protection, invisible without server access).
 *
 * <p>Every event belongs to exactly one {@link IndexingJob} via {@code jobId} - not a JPA
 * {@code @ManyToOne}, mirroring {@link IndexingJob#getLibraryId()}'s own plain-UUID style, since
 * nothing here ever needs to navigate back to the job entity itself. Rows are deleted in bulk by
 * {@code fk_indexing_run_events_job}'s {@code ON DELETE CASCADE} whenever {@link
 * IndexingJobService} prunes an old run (migration 037), not by any code in this class.
 *
 * <p>Implements {@link Persistable} with {@link #isNew()} always {@code true} - the same reason and
 * the same pattern as {@code io.opaa.audit.AuditLogEntry} (see that class's Javadoc): {@code id} is
 * client-generated in the constructor, never database-assigned, so without this Spring Data JPA
 * would treat every {@code save} as an update candidate and route it through {@code
 * EntityManager#merge} - an extra {@code SELECT} before every insert, on the hot path of every
 * executor's per-item skip/error recording (PR #604 review, nit a).
 */
@Entity
@Table(name = "indexing_run_events")
public class IndexingRunEvent implements Persistable<UUID> {

  @Id private UUID id;

  @Column(name = "job_id", nullable = false, updatable = false)
  private UUID jobId;

  @Enumerated(EnumType.STRING)
  @Column(name = "category", nullable = false, length = 30, updatable = false)
  private IndexingEventCategory category;

  @Column(name = "message", nullable = false, columnDefinition = "text", updatable = false)
  private String message;

  /**
   * The affected document name, file path or source URL - never the raw challenge/redirect target a
   * bot-protection page issued (#513 acceptance criteria). Nullable: an event is not always about a
   * single addressable item (e.g. an ALLOWLIST rejection of the run's own sourcePath still sets
   * this, but a hypothetical future run-wide event might not).
   */
  @Column(name = "reference", columnDefinition = "text", updatable = false)
  private String reference;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected IndexingRunEvent() {}

  public IndexingRunEvent(
      UUID jobId, IndexingEventCategory category, String message, String reference) {
    this.id = UUID.randomUUID();
    this.jobId = jobId;
    this.category = category;
    this.message = message;
    this.reference = reference;
    this.createdAt = Instant.now();
  }

  @Override
  public UUID getId() {
    return id;
  }

  /**
   * Always {@code true} - see the class Javadoc. There is no code path anywhere that loads an
   * {@code IndexingRunEvent} and passes it back to {@code save}.
   */
  @Override
  public boolean isNew() {
    return true;
  }

  public UUID getJobId() {
    return jobId;
  }

  public IndexingEventCategory getCategory() {
    return category;
  }

  public String getMessage() {
    return message;
  }

  public String getReference() {
    return reference;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
