package io.opaa.searchadmin;

import io.opaa.permission.PersonThreshold;

/**
 * The private libraries of an organization as one line of the index status, without names or ids.
 * Below the minimum group size of owners - zero included - the number is only "fewer than" and the
 * sums are absent, so no answer tells whether anyone keeps a private library.
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
   * The line for {@code libraries} private libraries of {@code owners} persons holding {@code
   * sums}: exact only where {@code threshold} discloses a number resting on that many persons.
   */
  static PrivateLibrarySummary of(
      long libraries, long owners, LibraryDocumentStats sums, PersonThreshold threshold) {
    if (!threshold.discloses(owners)) {
      return new PrivateLibrarySummary(null, threshold.minimum(), null, null, null);
    }
    return new PrivateLibrarySummary(
        libraries, null, sums.documentCount(), sums.failedDocumentCount(), sums.chunkCount());
  }
}
