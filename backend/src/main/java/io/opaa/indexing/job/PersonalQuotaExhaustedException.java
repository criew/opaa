package io.opaa.indexing.job;

/**
 * The storage quota across all private libraries of the run library's owner is reached. Not a
 * failure of the item or of the source: it passes every item catch ({@link EndsRun}), and {@code
 * IndexingRunTemplate} ends the run in an orderly way as incomplete with the category {@code
 * QUOTA_EXHAUSTED}. {@link #getMessage()} is the German message for the owner.
 */
public final class PersonalQuotaExhaustedException extends RuntimeException implements EndsRun {

  public PersonalQuotaExhaustedException(String message) {
    super(message);
  }
}
