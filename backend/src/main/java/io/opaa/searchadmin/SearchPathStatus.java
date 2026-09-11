package io.opaa.searchadmin;

/**
 * One search path's operational state.
 *
 * <p>{@link SearchPathCondition#INCOMPLETE} (vector path: documents still waiting) and {@link
 * SearchPathCondition#OUTDATED} (full-text path: rows below the current tsv version) are the states
 * docs/features/hybrid-retrieval.md demands be visible rather than only noticeable in bad answers.
 * Both are decided by exactly the per-library counts this page shows, so summary and table can
 * never disagree.
 *
 * @param affectedLibraryCount libraries the reported condition applies to.
 * @param libraryCount libraries holding at least one chunk at all.
 */
public record SearchPathStatus(
    SearchPathName path,
    SearchPathCondition condition,
    long affectedLibraryCount,
    long libraryCount) {

  public enum SearchPathName {
    VECTOR,
    FULL_TEXT
  }

  public enum SearchPathCondition {
    ACTIVE,
    DISABLED,
    INCOMPLETE,
    OUTDATED
  }
}
