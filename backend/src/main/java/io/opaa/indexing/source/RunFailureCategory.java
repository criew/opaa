package io.opaa.indexing.source;

/**
 * Why a run failed, where the frame tells the reason apart from the German message; travels with
 * the exception and into the log until the job records it.
 */
public enum RunFailureCategory {
  /** The source rejected the secret, also once the core was asked again ("Anmeldung abgelehnt"). */
  CREDENTIALS_REJECTED
}
