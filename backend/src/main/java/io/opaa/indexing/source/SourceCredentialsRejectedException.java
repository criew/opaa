package io.opaa.indexing.source;

import io.opaa.indexing.job.EndsRun;

/**
 * The source rejected the run's secret, also after the core was asked again once ({@link
 * RunCredentials#renewedAfterRejection}): the run ends with the source's German message under
 * {@link RunFailureCategory#CREDENTIALS_REJECTED}, past every item catch, and the frame tells the
 * port ({@link SourceConnectionResolver#credentialsRejected}).
 */
public class SourceCredentialsRejectedException extends IndexingRunFailedException
    implements EndsRun {

  public SourceCredentialsRejectedException(String message) {
    super(message);
  }

  public SourceCredentialsRejectedException(String message, Throwable cause) {
    super(message, cause);
  }

  public RunFailureCategory category() {
    return RunFailureCategory.CREDENTIALS_REJECTED;
  }
}
