package io.opaa.knowledge;

import io.opaa.common.ByteSizes;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/**
 * Enforces the per-library storage quota ({@link SharedLibraryQuota}) for uploads and connector
 * runs alike, scoped to a library, never to a person who uploaded. Only growth is checked: a
 * library above its quota keeps every document and still replaces one that does not grow. The
 * library check is not reserved: concurrent uploads may overshoot by at most one file each
 * (accepted). A private library also counts against its owner's {@link PersonalStorageQuota},
 * checked under {@link #holdIntake}, which serializes one owner's intake across every instance with
 * a transaction-scoped advisory lock (ADR-0053, Entscheidung 10).
 */
@Service
public class LibraryStorageQuotaService {

  private final DocumentRepository documentRepository;
  private final SharedLibraryQuota libraryQuota;
  private final PersonalStorageQuota personalQuota;
  private final PlatformTransactionManager transactionManager;

  public LibraryStorageQuotaService(
      DocumentRepository documentRepository,
      SharedLibraryQuota libraryQuota,
      PersonalStorageQuota personalQuota,
      PlatformTransactionManager transactionManager) {
    this.documentRepository = documentRepository;
    this.libraryQuota = libraryQuota;
    this.personalQuota = personalQuota;
    this.transactionManager = transactionManager;
  }

  /** The quota in force for {@code library} in bytes; {@code 0} means unlimited. */
  public long quotaBytes(KnowledgeLibrary library) {
    return libraryQuota.quotaBytesOf(library);
  }

  /**
   * The bytes {@code libraryId}'s documents occupy; a row without {@code file_size} counts as 0.
   * Every ingestion path records the size.
   */
  public long usedBytes(UUID libraryId) {
    return documentRepository.sumFileSizeByLibraryId(libraryId);
  }

  /**
   * Whether {@code additionalBytes} more would exceed the quota; never for an unlimited one, nor
   * for no growth. A caller replacing a document passes the growth over the stored version only.
   */
  public boolean wouldExceedQuota(KnowledgeLibrary library, long additionalBytes) {
    if (additionalBytes <= 0) {
      return false;
    }
    long quota = quotaBytes(library);
    if (quota <= 0) {
      return false;
    }
    return usedBytes(library.getId()) + additionalBytes > quota;
  }

  /**
   * Whether {@code library} may take in {@code additionalBytes} more, against its own quota and,
   * for a private library, its owner's. Call it under {@link #holdIntake} and store what it admits
   * before letting go; only an advisory check that stores nothing may skip the hold.
   */
  public QuotaVerdict verdictFor(KnowledgeLibrary library, long additionalBytes) {
    if (wouldExceedQuota(library, additionalBytes)) {
      return QuotaVerdict.LIBRARY_EXHAUSTED;
    }
    if (additionalBytes <= 0 || !library.isOwnerOnly()) {
      return QuotaVerdict.WITHIN;
    }
    long quota = personalQuota.quotaBytes();
    if (quota > 0 && personalQuota.usageOf(library.getOwnerUserId()) + additionalBytes > quota) {
      return QuotaVerdict.PERSON_EXHAUSTED;
    }
    return QuotaVerdict.WITHIN;
  }

  /**
   * Serializes the intake of all private libraries of {@code library}'s owner until closed, on
   * every instance; for a shared library it holds nothing. The hold is a transaction (joining the
   * caller's, if any) that takes the owner's advisory lock first, so the check and the rows written
   * under the hold commit together on close - or roll back, if a write marked it rollback-only.
   */
  public IntakeHold holdIntake(KnowledgeLibrary library) {
    if (!library.isOwnerOnly()) {
      return () -> {};
    }
    TransactionStatus status =
        transactionManager.getTransaction(
            new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRED));
    try {
      documentRepository.lockPersonalIntake(library.getOwnerUserId());
    } catch (RuntimeException e) {
      transactionManager.rollback(status);
      throw e;
    }
    return () -> {
      if (status.isRollbackOnly()) {
        transactionManager.rollback(status);
      } else {
        transactionManager.commit(status);
      }
    };
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
  public String quotaExceededMessage(KnowledgeLibrary library) {
    return quotaExceededMessage(library, usedBytes(library.getId()));
  }

  /**
   * Overload of {@link #quotaExceededMessage(KnowledgeLibrary)} for a caller that already knows
   * {@code library}'s current usage - avoids a second identical aggregate query per rejection.
   */
  public String quotaExceededMessage(KnowledgeLibrary library, long usedBytes) {
    // adaptive units, so a sub-GB quota never reads as "0 GB von 0 GB belegt" in every rejection
    return "Speicherkontingent der Bibliothek erschöpft ("
        + ByteSizes.format(usedBytes)
        + " von "
        + ByteSizes.format(quotaBytes(library))
        + " belegt)";
  }
}
