package io.opaa.indexing.source;

/**
 * The core could not turn a library's stored secret into one a connector can use - a refused or
 * unreadable key, a token endpoint that cannot be reached. The German message names the cause for
 * the person who maintains the source and never carries key, assertion or token; a run fails with
 * it.
 */
public class SourceCredentialsException extends IndexingRunFailedException {

  public SourceCredentialsException(String message) {
    super(message);
  }
}
