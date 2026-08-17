package io.opaa.library;

/**
 * The operation that opened or closed a {@link LibraryVisibilityHistory} interval (#238). Mirrored
 * by the database check constraint {@code chk_library_visibility_history_cause} (migration 018).
 */
public enum LibraryVisibilityHistoryCause {
  /** The library was created ({@code KnowledgeLibraryService#createLibrary}). */
  CREATED,

  /**
   * {@code visibility} or {@code listed} changed ({@code KnowledgeLibraryService#updateLibrary}) -
   * closes the previous interval and opens a new one with the new values.
   */
  VISIBILITY_CHANGED,

  /**
   * The interval was written by migration 018's backfill changeSet for a library that already
   * existed before this feature - reconstructed from {@code knowledge_libraries.created_at}, with
   * {@code actor_user_id} set to {@code owner_user_id} for a USER-owned library, else {@code null}
   * (code review of #238, finding 1).
   */
  BACKFILL
}
