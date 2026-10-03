package io.opaa.indexing.source;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.json.JsonMapper;

/**
 * The resumption state of one library whose connector lists its source completely (ADR-0023,
 * Entscheidung 4; ADR-0027, Entscheidung 3): which scopes - Confluence space keys, S3 {@code
 * bucket/prefix} keys - the running full sync has already completed, so an aborted run is resumed
 * scope by scope instead of from scratch; when the last full sync completed; and, for a connector
 * with an incremental mode, the anchor or the change cursors the next incremental run reads from.
 * Keyed by library alone. Absent for a library that never ran a full sync, and deleted by {@code
 * KnowledgeLibraryService} whenever the address or the selection changes: "no state" is how the
 * next run learns it has to be a full one that starts over.
 */
@Entity
@Table(name = "source_sync_state")
public class SourceSyncState {

  /** No scope key carries a line break (space keys and prefixes are validated on the library). */
  private static final String KEY_SEPARATOR = "\n";

  private static final JsonMapper CURSOR_JSON = JsonMapper.builder().build();

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

  /**
   * The change cursors per stream of a connector with a change log (ADR-0040, Entscheidung 6), as
   * {@link ChangeCursors} JSON; {@code null} for every other connector.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "change_cursors", columnDefinition = "jsonb")
  private String changeCursors;

  /**
   * The folder markers of the last complete full sync of a store that skips unchanged folders, as
   * {@link SubtreeMemory} JSON; {@code null} for every other connector.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "subtree_markers", columnDefinition = "jsonb")
  private String subtreeMarkers;

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
      ChangeCursors cursors = readChangeCursors();
      writeChangeCursors(new ChangeCursors(cursors.current(), Map.of()));
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
    ChangeCursors cursors = readChangeCursors();
    if (!cursors.pending().isEmpty()) {
      writeChangeCursors(new ChangeCursors(cursors.pending(), Map.of()));
    }
    touch();
  }

  /** The valid change cursor of every stream, keyed by stream, in no particular order. */
  public Map<String, String> changeCursors() {
    return readChangeCursors().current();
  }

  /**
   * The start cursors the full sync in progress holds back; {@link #completeFullSync(Instant)}
   * makes them the valid ones, and a full sync that starts over drops them.
   */
  public Map<String, String> pendingChangeCursors() {
    return readChangeCursors().pending();
  }

  /** Holds {@code cursors} (stream to start cursor) until the full sync completes. */
  public void holdPendingChangeCursors(Map<String, String> cursors) {
    writeChangeCursors(new ChangeCursors(readChangeCursors().current(), cursors));
    touch();
  }

  /**
   * The persisted form of the change cursors; {@code jsonb} keeps no key order, so neither do they.
   */
  record ChangeCursors(Map<String, String> current, Map<String, String> pending) {

    ChangeCursors {
      current = current == null ? Map.of() : Map.copyOf(current);
      pending = pending == null ? Map.of() : Map.copyOf(pending);
    }
  }

  private ChangeCursors readChangeCursors() {
    if (changeCursors == null) {
      return new ChangeCursors(Map.of(), Map.of());
    }
    return CURSOR_JSON.readValue(changeCursors, ChangeCursors.class);
  }

  private void writeChangeCursors(ChangeCursors cursors) {
    changeCursors =
        cursors.current().isEmpty() && cursors.pending().isEmpty()
            ? null
            : CURSOR_JSON.writeValueAsString(cursors);
  }

  /**
   * What the last complete full sync remembered of a store's folders (ADR-0040, Nachtrag
   * Nextcloud).
   *
   * @param basis what the markers were judged under (size bound, formats); another basis voids them
   * @param establishedAt when a full sync last listed every folder without them
   * @param containers per container key, the marker of every folder by hierarchy path
   */
  public record SubtreeMemory(
      String basis, Instant establishedAt, Map<String, Map<String, String>> containers) {

    public static final SubtreeMemory NONE = new SubtreeMemory(null, null, Map.of());

    public SubtreeMemory {
      containers = containers == null ? Map.of() : Map.copyOf(containers);
    }
  }

  /** The remembered folder markers, {@link SubtreeMemory#NONE} when there are none. */
  public SubtreeMemory subtreeMemory() {
    return subtreeMarkers == null
        ? SubtreeMemory.NONE
        : CURSOR_JSON.readValue(subtreeMarkers, SubtreeMemory.class);
  }

  /** Replaces the remembered folder markers; an empty memory clears them. */
  public void rememberSubtrees(SubtreeMemory memory) {
    subtreeMarkers =
        memory == null || memory.containers().isEmpty()
            ? null
            : CURSOR_JSON.writeValueAsString(memory);
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
