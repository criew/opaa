package io.opaa.searchadmin;

/**
 * One search path's operational state.
 *
 * <p>{@link SearchPathCondition#INCOMPLETE} is the state docs/features/hybrid-retrieval.md demands
 * be visible rather than only noticeable in bad answers: the path runs, but not over the whole
 * bestand. For the full-text path it is decided by exactly the count the completion gate reads, so
 * the display and the gate can never disagree.
 *
 * @param incompleteLibraryCount libraries this path cannot yet search completely.
 * @param libraryCount libraries holding at least one chunk at all.
 */
public record SearchPathStatus(
    SearchPathName path,
    SearchPathCondition condition,
    long incompleteLibraryCount,
    long libraryCount) {

  public enum SearchPathName {
    VECTOR,
    FULL_TEXT
  }

  public enum SearchPathCondition {
    ACTIVE,
    DISABLED,
    INCOMPLETE
  }
}
