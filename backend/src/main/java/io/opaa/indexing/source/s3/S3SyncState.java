package io.opaa.indexing.source.s3;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The run state of one S3 library (ADR-0027, Entscheidung 3, "Wiederaufnahme"): which scopes the
 * running full sync has already listed completely - as {@code bucket/prefix} keys, never a list
 * index, so a removed scope shifts nothing - and when the last full sync completed. A run after an
 * interrupted one lists every scope again, unfinished scopes first, and only saves the downloads;
 * no continuation token is ever stored, because the reconciliation set is built per run. Keyed by
 * library alone; deleted by {@code KnowledgeLibraryService} whenever the endpoint or the scopes
 * change, so the next run starts from scratch.
 */
@Entity
@Table(name = "s3_sync_state")
public class S3SyncState {

  /** A scope key carries no line break (the prefix rejects control characters). */
  private static final String KEY_SEPARATOR = "\n";

  @Id private UUID id;

  @Column(name = "library_id", nullable = false, unique = true)
  private UUID libraryId;

  /** The job of the full sync in progress; {@code null} once it completed or before the first. */
  @Column(name = "full_sync_job_id")
  private UUID fullSyncJobId;

  @Column(name = "completed_scope_keys", columnDefinition = "text")
  private String completedScopeKeys;

  @Column(name = "full_sync_completed_at")
  private Instant fullSyncCompletedAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected S3SyncState() {}

  public S3SyncState(UUID libraryId) {
    this.id = UUID.randomUUID();
    this.libraryId = libraryId;
    this.updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getLibraryId() {
    return libraryId;
  }

  public UUID getFullSyncJobId() {
    return fullSyncJobId;
  }

  public Instant getFullSyncCompletedAt() {
    return fullSyncCompletedAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** Whether a previous full sync was interrupted before it completed. */
  public boolean isFullSyncInterrupted() {
    return fullSyncJobId != null && fullSyncCompletedAt == null;
  }

  /** The scopes the interrupted full sync had already listed completely, in completion order. */
  public Set<String> completedScopeKeys() {
    if (completedScopeKeys == null || completedScopeKeys.isBlank()) {
      return Set.of();
    }
    return new LinkedHashSet<>(Arrays.asList(completedScopeKeys.split(KEY_SEPARATOR)));
  }

  /**
   * Starts a full sync under {@code jobId}. Resumes - keeps the completed scopes - when the
   * previous one was interrupted, starts clean otherwise.
   */
  public void beginFullSync(UUID jobId) {
    if (!isFullSyncInterrupted()) {
      completedScopeKeys = null;
    }
    fullSyncJobId = jobId;
    fullSyncCompletedAt = null;
    touch();
  }

  public void markScopeCompleted(String scopeKey) {
    Set<String> keys = new LinkedHashSet<>(completedScopeKeys());
    keys.add(scopeKey);
    completedScopeKeys = String.join(KEY_SEPARATOR, keys);
    touch();
  }

  /** Ends the full sync: every scope was listed completely and the bestand is reconciled. */
  public void completeFullSync(Instant completedAt) {
    fullSyncCompletedAt = completedAt;
    completedScopeKeys = null;
    fullSyncJobId = null;
    touch();
  }

  private void touch() {
    updatedAt = Instant.now();
  }
}
