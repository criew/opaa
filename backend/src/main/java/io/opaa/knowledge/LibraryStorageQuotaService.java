package io.opaa.knowledge;

import io.opaa.common.ByteSizes;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;

/**
 * Enforces the per-library storage quota (#119, Maintainer-Entscheidung: Standardkontingent je
 * Bibliothek, {@link LibraryProperties#quotaBytes}). Shared by every ingestion path that stores
 * document content - the upload endpoint ({@code LibraryDocumentService}) and the
 * FILESYSTEM/HTTP_DIRECTORY/RSS_FEED connector paths ({@code DocumentIngestService}) - so a library
 * cannot grow past its quota through either route.
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
 *
 * <p>A private library is held to the {@link PersonalStorageQuota} of its owner as well ({@link
 * #verdictFor}). That check runs under {@link #holdIntake}, which serializes the intake of one
 * owner's private libraries inside this single instance (ADR-0021): what is admitted is stored
 * before the next check, so concurrent runs of several libraries of one person never overshoot it.
 */
@Service
public class LibraryStorageQuotaService {

  private static final int OWNER_STRIPES = 64;

  private final DocumentRepository documentRepository;
  private final LibraryProperties libraryProperties;
  private final PersonalStorageQuota personalQuota;
  private final ReentrantLock[] ownerLocks = new ReentrantLock[OWNER_STRIPES];

  public LibraryStorageQuotaService(
      DocumentRepository documentRepository,
      LibraryProperties libraryProperties,
      PersonalStorageQuota personalQuota) {
    this.documentRepository = documentRepository;
    this.libraryProperties = libraryProperties;
    this.personalQuota = personalQuota;
    for (int i = 0; i < OWNER_STRIPES; i++) {
      ownerLocks[i] = new ReentrantLock();
    }
  }

  /**
   * The configured per-library quota in bytes ({@code application.yml}'s own default resolves to 10
   * GiB when unset), or {@code <= 0} if the operator has configured {@link
   * LibraryProperties#quotaBytes} to mean <em>unbegrenzt</em> (#119, PR #700 review finding 2) -
   * see that property's own Javadoc for why {@code 0}/negative is a real configuration here rather
   * than being normalized away.
   */
  public long quotaBytes() {
    return libraryProperties.quotaBytes();
  }

  /**
   * The bytes {@code libraryId}'s documents currently occupy, summed across all of them.
   *
   * <p><b>A pre-existing row with no recorded {@code file_size} (nullable) counts as {@code 0}, not
   * as unknown (PR #700 review, finding 6).</b> Every ingestion path this class enforces a quota on
   * writes a size unconditionally (upload: {@code Files.size(storedFile)}; FILESYSTEM: {@code
   * Files.size(file)}; HTTP_DIRECTORY/RSS attachments: {@code Files.size(tempFile)}; an RSS entry's
   * own text: {@code contentBytes.length}) - a {@code NULL} row can therefore only be a document
   * that predates this column ever being populated, not one this service itself created. Such a row
   * understates a library's true usage by exactly its own size; there is no way to recover that
   * lost figure retroactively, so this is an accepted, documented gap rather than a hidden one.
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
   * Whether {@code library} may take in {@code additionalBytes} more: {@link
   * QuotaVerdict#LIBRARY_EXHAUSTED} as {@link #wouldExceedQuota(UUID, long)} says, else for a
   * private library {@link QuotaVerdict#PERSON_EXHAUSTED} when the use of all private libraries of
   * its owner would pass the {@link PersonalStorageQuota}. Call it under {@link #holdIntake} and
   * store what it admits before letting go.
   */
  public QuotaVerdict verdictFor(KnowledgeLibrary library, long additionalBytes) {
    if (wouldExceedQuota(library.getId(), additionalBytes)) {
      return QuotaVerdict.LIBRARY_EXHAUSTED;
    }
    if (!library.isOwnerOnly()) {
      return QuotaVerdict.WITHIN;
    }
    long quota = personalQuota.quotaBytes();
    if (quota > 0 && personalQuota.usageOf(library.getOwnerUserId()) + additionalBytes > quota) {
      return QuotaVerdict.PERSON_EXHAUSTED;
    }
    return QuotaVerdict.WITHIN;
  }

  /**
   * Serializes the intake of all private libraries of {@code library}'s owner until closed; for a
   * shared library it holds nothing.
   */
  public IntakeHold holdIntake(KnowledgeLibrary library) {
    if (!library.isOwnerOnly()) {
      return () -> {};
    }
    ReentrantLock lock =
        ownerLocks[Math.floorMod(library.getOwnerUserId().hashCode(), OWNER_STRIPES)];
    lock.lock();
    return lock::unlock;
  }

  /**
   * The German message for the owner of {@code library} once {@link #verdictFor} said {@link
   * QuotaVerdict#PERSON_EXHAUSTED}; it names her own use and the limit.
   */
  public String personalQuotaExceededMessage(KnowledgeLibrary library) {
    return "Speicherkontingent Ihrer privaten Bibliotheken erschöpft ("
        + ByteSizes.format(personalQuota.usageOf(library.getOwnerUserId()))
        + " von "
        + ByteSizes.format(personalQuota.quotaBytes())
        + " belegt)";
  }

  /** A held intake; closing it lets the next intake of the same owner in. */
  @FunctionalInterface
  public interface IntakeHold extends AutoCloseable {
    @Override
    void close();
  }

  /**
   * A German, user-facing explanation of why {@code libraryId} rejected an addition - the exact
   * wording #119's acceptance criteria specify ("Speicherkontingent der Bibliothek erschöpft (X von
   * Y belegt)"), reused verbatim by both the upload endpoint's 413 response ({@code
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
    // adaptive units, so a sub-GB quota never reads as "0 GB von 0 GB belegt" in every rejection
    return "Speicherkontingent der Bibliothek erschöpft ("
        + ByteSizes.format(usedBytes)
        + " von "
        + ByteSizes.format(quotaBytes())
        + " belegt)";
  }
}
