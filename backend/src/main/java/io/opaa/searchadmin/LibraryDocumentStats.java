package io.opaa.searchadmin;

import java.time.Instant;
import java.util.UUID;

/**
 * The {@code documents} aggregates of one library: how many rows there are per status, how many
 * chunks they record, when the last one was indexed, and how many are geführt as indexed with null
 * or auffällig wenige chunks.
 *
 * @param lastIndexedAt latest {@code indexed_at} across the library, or {@code null} if none ran.
 */
public record LibraryDocumentStats(
    UUID libraryId,
    long documentCount,
    long indexedDocumentCount,
    long pendingDocumentCount,
    long failedDocumentCount,
    long lowChunkDocumentCount,
    long chunkCount,
    Instant lastIndexedAt) {

  /** The zero row for a library that has no document at all. */
  public static LibraryDocumentStats empty(UUID libraryId) {
    return new LibraryDocumentStats(libraryId, 0, 0, 0, 0, 0, 0, null);
  }
}
