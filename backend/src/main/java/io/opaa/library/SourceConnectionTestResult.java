package io.opaa.library;

import io.opaa.api.types.ConfluenceEdition;

/**
 * The outcome of a {@link SourceConnectionTestService} probe - the domain counterpart of the
 * generated {@code SourceConnectionTestResponse}.
 *
 * @param message German, user-facing text, never a raw exception message (#514).
 * @param documentCount {@code null} when {@code reachable} is {@code false} - a failed probe never
 *     reports a count for the source it could not read.
 */
public record SourceConnectionTestResult(
    boolean reachable,
    String message,
    Long documentCount,
    ConfluenceEdition confluenceEdition,
    Boolean credentialsVerified) {

  public SourceConnectionTestResult(boolean reachable, String message, Long documentCount) {
    this(reachable, message, documentCount, null, null);
  }

  public SourceConnectionTestResult {
    if (!reachable && documentCount != null) {
      throw new IllegalArgumentException("documentCount must be null when reachable is false");
    }
  }
}
