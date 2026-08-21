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
 * could in principle both pass this check and jointly overshoot the quota; unlike a same-checksum
 * race (where {@code uk_documents_library_checksum} actually catches and rejects the losing
 * request), nothing here catches this one - the overshoot simply stays. The resulting bound is
 * bounded, not caught: at most (number of genuinely concurrent uploads into the same library) x
 * (the 50 MiB single-file limit {@link UploadProperties#maxFileSize}) of permanent overshoot -
 * narrow enough in practice that serializing every write behind a lock for it was not judged
 * worthwhile.
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

  /**
   * The configured per-library quota in bytes ({@code application.yml}'s own default resolves to 10
   * GiB when unset), or {@code <= 0} if the operator has configured {@link
   * UploadProperties#libraryQuotaBytes} to mean <em>unbegrenzt</em> (#119, PR #700 review finding
   * 2) - see that property's own Javadoc for why {@code 0}/negative is a real configuration here
   * rather than being normalized away.
   */
  public long quotaBytes() {
    return uploadProperties.libraryQuotaBytes();
  }

  /**
   * The bytes {@code libraryId}'s documents currently occupy, summed across all of them.
   *
   * <p><b>A pre-existing row with no recorded {@code file_size} (nullable, migration 002) counts as
   * {@code 0}, not as unknown (PR #700 review, finding 6).</b> Every ingestion path this class
   * enforces a quota on writes a size unconditionally (upload: {@code Files.size(storedFile)};
   * FILESYSTEM: {@code Files.size(file)}; HTTP_DIRECTORY/RSS attachments: {@code
   * Files.size(tempFile)}; an RSS entry's own text: {@code contentBytes.length}) - a {@code NULL}
   * row can therefore only be a document that predates this column ever being populated, not one
   * this service itself created. Such a row understates a library's true usage by exactly its own
   * size; there is no way to recover that lost figure retroactively, so this is an accepted,
   * documented gap rather than a hidden one.
   */
  public long usedBytes(UUID libraryId) {
    return documentRepository.sumFileSizeByLibraryId(libraryId);
  }

  /**
   * Whether adding {@code additionalBytes} to {@code libraryId}'s current usage would exceed the
   * configured quota - always {@code false} when {@link #quotaBytes()} is {@code <= 0} (unbegrenzt,
   * #119). Callers that replace an existing document (a same-checksum retry, a connector re-index)
   * should call this <em>after</em> removing the row/chunks being replaced, so {@link #usedBytes}
   * already reflects the deletion and the check measures the true delta rather than double-counting
   * the content being superseded.
   */
  public boolean wouldExceedQuota(UUID libraryId, long additionalBytes) {
    long quota = quotaBytes();
    if (quota <= 0) {
      return false;
    }
    return usedBytes(libraryId) + additionalBytes > quota;
  }

  /**
   * A German, user-facing explanation of why {@code libraryId} rejected an addition - the exact
   * wording #119's acceptance criteria specify ("Speicherkontingent der Bibliothek erschöpft (X von
   * Y belegt)"), reused verbatim by both the upload endpoint's 413 response ({@link
   * LibraryDocumentService}) and the connector run protocol event ({@code
   * IndexingEventCategory#REJECTED}, #604). Only ever called once {@link #wouldExceedQuota} has
   * already returned {@code true} for the same library, so {@link #quotaBytes()} is guaranteed
   * positive here - a caller never sees "0 von 0 GB".
   */
  public String quotaExceededMessage(UUID libraryId) {
    return quotaExceededMessage(libraryId, usedBytes(libraryId));
  }

  /**
   * Overload of {@link #quotaExceededMessage(UUID)} for a caller that already knows {@code
   * libraryId}'s current usage (typically from its own preceding {@link #wouldExceedQuota} call) -
   * avoids a second identical aggregate query per rejected document (PR #700 review, nit 8).
   */
  public String quotaExceededMessage(UUID libraryId, long usedBytes) {
    return "Speicherkontingent der Bibliothek erschöpft ("
        + formatBytes(usedBytes)
        + " von "
        + formatBytes(quotaBytes())
        + " belegt)";
  }

  /**
   * Formats a byte count adaptively (B/KB/MB/GB/TB, one decimal above B, German locale) - mirrors
   * the frontend's own {@code formatFileSize} (frontend/src/utils/labels.ts) so the same figure
   * reads the same way in a quota rejection message as it does on the library detail page (PR #700
   * review, finding 3). A fixed "GB" unit would render a sub-MB quota as the same "0,0 GB von 0,0
   * GB belegt" for every rejection, indistinguishable from every other one in a connector run's
   * protocol (up to 500 events, see {@code IndexingRunEventRecorder}).
   */
  private String formatBytes(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    }
    String[] units = {"KB", "MB", "GB", "TB"};
    double value = bytes / 1024.0;
    int unitIndex = 0;
    while (value >= 1024 && unitIndex < units.length - 1) {
      value /= 1024;
      unitIndex++;
    }
    return String.format(Locale.GERMANY, "%.1f %s", value, units[unitIndex]);
  }
}
