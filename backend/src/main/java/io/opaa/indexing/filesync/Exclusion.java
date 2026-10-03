package io.opaa.indexing.filesync;

/**
 * Why a listed entry is not fetched, decided by the store from what the listing shows. Every text
 * is German and user-facing.
 */
public sealed interface Exclusion {

  /**
   * No document at all (a folder marker, a shortcut): present, skipped and counted in one {@code
   * UNSUPPORTED_FORMAT} note per run, {@code count + note}.
   */
  record NotADocument(String note) implements Exclusion {}

  /**
   * Outside the library's include and exclude patterns: not part of the bestand - never present, so
   * a stored document of it is removed by a complete listing - and counted in one {@code REJECTED}
   * note per run, {@code count + note}.
   */
  record Deselected(String note) implements Exclusion {}

  /**
   * At the source but not readable as it is (an archive class without a restore): present, skipped
   * with {@code message} as a {@code REJECTED} entry; a stored document keeps its version.
   */
  record Unavailable(String message) implements Exclusion {}
}
