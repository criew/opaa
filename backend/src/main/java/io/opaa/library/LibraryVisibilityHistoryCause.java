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
  VISIBILITY_CHANGED
}
