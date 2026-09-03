package io.opaa.indexing.source.confluence;

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
 * The run state of one CONFLUENCE library (ADR-0023, Entscheidung 4, "Anker und Wiederaufnahme"):
 * which spaces the running full sync has already completed - so an aborted run is resumed space by
 * space, not from scratch - and, once a full sync completed, the anchor the next incremental run
 * searches from. Keyed by library alone (a library carries exactly one instance; two libraries
 * against the same instance keep separate states). Absent for a library that never completed a full
 * run, and deleted by {@code KnowledgeLibraryService} whenever the address or the space selection
 * changes: "no state" is how the next run learns it has to be a full one.
 */
@Entity
@Table(name = "confluence_sync_state")
public class ConfluenceSyncState {

  /**
   * Space keys never contain a line break (validated on the library), so it is a safe separator.
   */
  private static final String KEY_SEPARATOR = "\n";

  @Id private UUID id;

  @Column(name = "library_id", nullable = false, unique = true)
  private UUID libraryId;

  /** The job of the full sync in progress; {@code null} once it completed or before the first. */
  @Column(name = "full_sync_job_id")
  private UUID fullSyncJobId;

  @Column(name = "completed_space_keys", columnDefinition = "text")
  private String completedSpaceKeys;

  @Column(name = "full_sync_completed_at")
  private Instant fullSyncCompletedAt;

  /** Where the next incremental run searches from (#1139) - the start of the last full sync. */
  @Column(name = "incremental_anchor")
  private Instant incrementalAnchor;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected ConfluenceSyncState() {}

  public ConfluenceSyncState(UUID libraryId) {
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

  /** The spaces the interrupted full sync had already completed, in completion order. */
  public Set<String> completedSpaceKeys() {
    if (completedSpaceKeys == null || completedSpaceKeys.isBlank()) {
      return Set.of();
    }
    return new LinkedHashSet<>(Arrays.asList(completedSpaceKeys.split(KEY_SEPARATOR)));
  }

  /**
   * Starts a full sync under {@code jobId}. Resumes - keeps the completed spaces - when the
   * previous one was interrupted, starts clean otherwise.
   */
  public void beginFullSync(UUID jobId) {
    if (!isFullSyncInterrupted()) {
      completedSpaceKeys = null;
    }
    fullSyncJobId = jobId;
    fullSyncCompletedAt = null;
    touch();
  }

  public void markSpaceCompleted(String spaceKey) {
    Set<String> keys = new LinkedHashSet<>(completedSpaceKeys());
    keys.add(spaceKey);
    completedSpaceKeys = String.join(KEY_SEPARATOR, keys);
    touch();
  }

  /**
   * Ends the full sync: every selected space was listed completely, the bestand is reconciled, and
   * {@code anchor} (the run's start) is where the next incremental run picks up; {@code
   * completedAt} (the caller's clock) is what the full-sync interval is measured from.
   */
  public void completeFullSync(Instant anchor, Instant completedAt) {
    fullSyncCompletedAt = completedAt;
    incrementalAnchor = anchor;
    completedSpaceKeys = null;
    fullSyncJobId = null;
    touch();
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
