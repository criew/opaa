package io.opaa.library;

import io.opaa.api.types.ConfluenceEdition;
import java.util.List;

/**
 * The outcome of a {@link SourceConnectionTestService} probe - the domain counterpart of the
 * generated {@code SourceConnectionTestResponse}.
 *
 * @param message German, user-facing text, never a raw exception message (#514).
 * @param documentCount {@code null} when {@code reachable} is {@code false} - a failed probe never
 *     reports a count for the source it could not read.
 * @param s3Scopes one finding per probed scope of an {@code S3} test (#1376), {@code null} for
 *     every other type
 */
public record SourceConnectionTestResult(
    boolean reachable,
    String message,
    Long documentCount,
    ConfluenceEdition confluenceEdition,
    Boolean credentialsVerified,
    List<S3ScopeCheck> s3Scopes) {

  public SourceConnectionTestResult(boolean reachable, String message, Long documentCount) {
    this(reachable, message, documentCount, null, null, null);
  }

  public SourceConnectionTestResult(
      boolean reachable,
      String message,
      Long documentCount,
      ConfluenceEdition confluenceEdition,
      Boolean credentialsVerified) {
    this(reachable, message, documentCount, confluenceEdition, credentialsVerified, null);
  }

  public SourceConnectionTestResult {
    if (!reachable && documentCount != null) {
      throw new IllegalArgumentException("documentCount must be null when reachable is false");
    }
    s3Scopes = s3Scopes == null ? null : List.copyOf(s3Scopes);
  }
}
