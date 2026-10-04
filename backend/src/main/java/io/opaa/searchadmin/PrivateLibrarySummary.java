package io.opaa.searchadmin;

import io.opaa.permission.PersonThreshold;

/**
 * The private libraries of an organization as one line of the index status, without names or ids.
 * Below the minimum group size of owners - zero included - the number is only "fewer than" and the
 * sums are absent, so no answer tells whether anyone keeps a private library.
 *
 * @param libraryCount the exact number, {@code null} where only {@code libraryCountFewerThan} may
 *     be told
 * @param scheduledErasureCount how many are due to be erased, a part of the number: exact only
 *     where both its owners and the owners of the others reach the minimum; {@code null} otherwise
 * @param scheduledErasureCountFewerThan the minimum, where the part rests on fewer owners
 */
public record PrivateLibrarySummary(
    Long libraryCount,
    Integer libraryCountFewerThan,
    Long documentCount,
    Long failedDocumentCount,
    Long chunkCount,
    Long scheduledErasureCount,
    Integer scheduledErasureCountFewerThan) {

  /** Without the part due to be erased. */
  public PrivateLibrarySummary(
      Long libraryCount,
      Integer libraryCountFewerThan,
      Long documentCount,
      Long failedDocumentCount,
      Long chunkCount) {
    this(
        libraryCount,
        libraryCountFewerThan,
        documentCount,
        failedDocumentCount,
        chunkCount,
        null,
        null);
  }

  /**
   * The line for {@code libraries} private libraries of {@code owners} persons holding {@code
   * sums}: exact only where {@code threshold} discloses a number resting on that many persons.
   */
  static PrivateLibrarySummary of(
      long libraries, long owners, LibraryDocumentStats sums, PersonThreshold threshold) {
    return of(libraries, owners, sums, threshold, null);
  }

  /** As above, with the part of them due to be erased, masked as a part ({@code disclosesPart}). */
  static PrivateLibrarySummary of(
      long libraries,
      long owners,
      LibraryDocumentStats sums,
      PersonThreshold threshold,
      ScheduledPart scheduled) {
    Long scheduledCount = null;
    Integer scheduledFewerThan = null;
    if (scheduled != null) {
      if (threshold.disclosesPart(scheduled.owners(), scheduled.otherOwners())) {
        scheduledCount = scheduled.libraries();
      } else if (!threshold.discloses(scheduled.owners())) {
        scheduledFewerThan = threshold.minimum();
      }
    }
    if (!threshold.discloses(owners)) {
      return new PrivateLibrarySummary(
          null, threshold.minimum(), null, null, null, scheduledCount, scheduledFewerThan);
    }
    return new PrivateLibrarySummary(
        libraries,
        null,
        sums.documentCount(),
        sums.failedDocumentCount(),
        sums.chunkCount(),
        scheduledCount,
        scheduledFewerThan);
  }

  /** The libraries due to be erased, their owners and the owners of all other private ones. */
  record ScheduledPart(long libraries, long owners, long otherOwners) {}
}
