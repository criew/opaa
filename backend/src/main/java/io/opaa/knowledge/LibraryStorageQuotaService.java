package io.opaa.knowledge;

import io.opaa.common.ByteSizes;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;

/**
 * Enforces the per-library storage quota ({@link LibraryProperties#quotaBytes}) for uploads and
 * connector runs alike, scoped to a library, never to a person who uploaded. The library check is
 * not reserved: concurrent uploads may overshoot by at most one file each (accepted). A private
 * library also counts against its owner's {@link PersonalStorageQuota}, checked under {@link
 * #holdIntake}, which serializes one owner's intake in this single instance (ADR-0021).
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

  /** The configured per-library quota in bytes; {@code <= 0} means unlimited. */
  public long quotaBytes() {
    return libraryProperties.quotaBytes();
  }

  /**
   * The bytes {@code libraryId}'s documents occupy; a row without {@code file_size} counts as 0.
   * Every ingestion path records the size.
   */
  public long usedBytes(UUID libraryId) {
    return documentRepository.sumFileSizeByLibraryId(libraryId);
  }

  /**
   * Whether {@code additionalBytes} more would exceed the quota; never for an unlimited one. A
   * caller replacing a document calls this after removing the old row, so it is not counted twice.
   */
  public boolean wouldExceedQuota(UUID libraryId, long additionalBytes) {
    long quota = quotaBytes();
    if (quota <= 0) {
      return false;
    }
    return usedBytes(libraryId) + additionalBytes > quota;
  }

  /**
   * Whether {@code library} may take in {@code additionalBytes} more, against its own quota and,
   * for a private library, its owner's. Call it under {@link #holdIntake} and store what it admits
   * before letting go; only an advisory check that stores nothing may skip the hold.
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
   * The German rejection for the upload's 413 and the run protocol alike, "Speicherkontingent der
   * Bibliothek erschöpft (X von Y belegt)"; called only after {@link #wouldExceedQuota} said so.
   */
  public String quotaExceededMessage(UUID libraryId) {
    return quotaExceededMessage(libraryId, usedBytes(libraryId));
  }

  /**
   * Overload of {@link #quotaExceededMessage(UUID)} for a caller that already knows {@code
   * libraryId}'s current usage (typically from its own preceding {@link #wouldExceedQuota} call) -
   * avoids a second identical aggregate query per rejected document.
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
