package io.opaa.library;

import io.opaa.indexing.DocumentRepository;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Enforces the per-library storage quota (#119, Maintainer-Entscheidung: Standardkontingent je
 * Bibliothek, {@link UploadProperties#libraryQuotaBytes}). Shared by every ingestion path that
 * stores document content - the upload endpoint ({@link LibraryDocumentService}) and the
 * FILESYSTEM/HTTP_DIRECTORY/RSS_FEED connector paths ({@code
 * io.opaa.indexing.FileProcessingService}) - so a library cannot grow past its quota through either
 * route.
 *
 * <p><b>Datenschutz (#216, "kein personenbezogener Auswertungspfad"):</b> every method here is
 * scoped to a library, never to an individual user - {@link
 * DocumentRepository#sumFileSizeByLibraryId} sums every document in the library regardless of who
 * uploaded it. There is deliberately no per-user usage query anywhere in this class.
 *
 * <p>The quota check compares the library's usage <em>at the moment of the call</em> against {@link
 * #quotaBytes()} plus the additional bytes a caller is about to add - it is not transactionally
 * reserved. Two concurrent uploads into the same library, both just under the remaining headroom,
 * could in principle both pass this check and jointly overshoot the quota slightly; the same
 * accepted, narrow race every other per-library capacity check in this codebase (e.g. the checksum
 * dedup race {@code LibraryDocumentService#uploadDocument} guards with a unique constraint)
 * tolerates rather than serializing every write behind a lock for.
 */
@Service
public class LibraryStorageQuotaService {

  private final DocumentRepository documentRepository;
  private final UploadProperties uploadProperties;

  public LibraryStorageQuotaService(
      DocumentRepository documentRepository, UploadProperties uploadProperties) {
    this.documentRepository = documentRepository;
    this.uploadProperties = uploadProperties;
  }

  /** The configured per-library quota in bytes (default 10 GiB, see {@link UploadProperties}). */
  public long quotaBytes() {
    return uploadProperties.libraryQuotaBytes();
  }

  /** The bytes {@code libraryId}'s documents currently occupy, summed across all of them. */
  public long usedBytes(UUID libraryId) {
    return documentRepository.sumFileSizeByLibraryId(libraryId);
  }

  /**
   * Whether adding {@code additionalBytes} to {@code libraryId}'s current usage would exceed the
   * configured quota. Callers that replace an existing document (a same-checksum retry, a connector
   * re-index) should call this <em>after</em> removing the row/chunks being replaced, so {@link
   * #usedBytes} already reflects the deletion and the check measures the true delta rather than
   * double-counting the content being superseded.
   */
  public boolean wouldExceedQuota(UUID libraryId, long additionalBytes) {
    return usedBytes(libraryId) + additionalBytes > quotaBytes();
  }

  /**
   * A German, user-facing explanation of why {@code libraryId} rejected an addition - the exact
   * wording #119's acceptance criteria specify ("Speicherkontingent der Bibliothek erschöpft (X von
   * Y belegt)"), reused verbatim by both the upload endpoint's 413 response ({@link
   * LibraryDocumentService}) and the connector run protocol event ({@code
   * IndexingEventCategory#REJECTED}, #604).
   */
  public String quotaExceededMessage(UUID libraryId) {
    return "Speicherkontingent der Bibliothek erschöpft ("
        + formatBytes(usedBytes(libraryId))
        + " von "
        + formatBytes(quotaBytes())
        + " belegt)";
  }

  /** Formats a byte count as a German-locale GB figure with one decimal, e.g. "10,0 GB". */
  private String formatBytes(long bytes) {
    double gigabytes = bytes / (1024.0 * 1024.0 * 1024.0);
    return String.format(Locale.GERMANY, "%.1f GB", gigabytes);
  }
}
