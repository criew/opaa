package io.opaa.indexing.source;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The resumption state of one library whose connector lists its source completely (ADR-0023,
 * Entscheidung 4; ADR-0027, Entscheidung 3): which scopes - Confluence space keys, S3 {@code
 * bucket/prefix} keys - the running full sync has already completed, so an aborted run is resumed
 * scope by scope instead of from scratch; when the last full sync completed; and, for a connector
 * with an incremental mode, the anchor the next incremental run searches from. Keyed by library
 * alone. Absent for a library that never ran a full sync, and deleted by {@code
 * KnowledgeLibraryService} whenever the address or the selection changes: "no state" is how the
 * next run learns it has to be a full one that starts over.
 */
@Entity
@Table(name = "source_sync_state")
public class SourceSyncState {

  /** No scope key carries a line break (space keys and prefixes are validated on the library). */
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

  /**
   * Where the next incremental run searches from - the start of the last full sync, advanced by
   * every clean incremental run. Stays {@code null} for a connector without an incremental mode.
   */
  @Column(name = "incremental_anchor")
  private Instant incrementalAnchor;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected SourceSyncState() {}

  public SourceSyncState(UUID libraryId) {
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

  public Instant getIncrementalAnchor() {
    return incrementalAnchor;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** Whether a previous full sync was interrupted before it completed. */
  public boolean isFullSyncInterrupted() {
    return fullSyncJobId != null && fullSyncCompletedAt == null;
  }

  /** The scopes the interrupted full sync had already completed, in completion order. */
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

  /**
   * Ends the full sync: every scope was listed completely and the bestand is reconciled; {@code
   * completedAt} (the caller's clock) is what the full-sync interval is measured from. The
   * incremental anchor is left as it is - the variant for a connector without an incremental mode.
   */
  public void completeFullSync(Instant completedAt) {
    fullSyncCompletedAt = completedAt;
    completedScopeKeys = null;
    fullSyncJobId = null;
    touch();
  }

  /**
   * Ends the full sync like {@link #completeFullSync(Instant)} and sets {@code anchor} (the run's
   * start) as where the next incremental run picks up.
   */
  public void completeFullSync(Instant completedAt, Instant anchor) {
    completeFullSync(completedAt);
    incrementalAnchor = anchor;
  }

  /**
   * An incremental run completed without failures: the next one searches from {@code anchor} (the
   * start of the run that just finished, minus the caller's overlap) - never from its end, so
   * changes during the run are not lost (ADR-0023, Entscheidung 4).
   */
  public void advanceIncrementalAnchor(Instant anchor) {
    incrementalAnchor = anchor;
    touch();
  }

  /**
   * Whether the next run has to be a full one: nothing completed yet, the last full sync was
   * interrupted, or the last completed one is older than {@code interval}. Only a completed full
   * sync leaves the anchor an incremental run needs.
   */
  public boolean isFullSyncDue(Duration interval, Instant now) {
    return fullSyncCompletedAt == null
        || isFullSyncInterrupted()
        || incrementalAnchor == null
        || !fullSyncCompletedAt.plus(interval).isAfter(now);
  }

  private void touch() {
    updatedAt = Instant.now();
  }
}
