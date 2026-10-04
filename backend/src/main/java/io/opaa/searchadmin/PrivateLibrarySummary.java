package io.opaa.searchadmin;

/**
 * The private libraries of an organization as one line of the index status, without names or ids.
 * Below the minimum group size - zero included - the number is only "fewer than" and the sums are
 * absent, so no answer tells whether anyone keeps a private library.
 *
 * @param libraryCount the exact number, {@code null} where only {@code libraryCountFewerThan} may
 *     be told
 */
public record PrivateLibrarySummary(
    Long libraryCount,
    Integer libraryCountFewerThan,
    Long documentCount,
    Long failedDocumentCount,
    Long chunkCount) {

  /**
   * The line for {@code libraries} private libraries holding {@code sums}, masked below {@code
   * minimum}.
   */
  static PrivateLibrarySummary of(long libraries, LibraryDocumentStats sums, int minimum) {
    if (libraries < minimum) {
      return new PrivateLibrarySummary(null, minimum, null, null, null);
    }
    return new PrivateLibrarySummary(
        libraries, null, sums.documentCount(), sums.failedDocumentCount(), sums.chunkCount());
  }
}
