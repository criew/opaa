package io.opaa.indexing.source;

/**
 * The outcome of a {@link SourceConnector#testConnection} probe - the domain counterpart of the
 * generated {@code SourceConnectionTestResponse}.
 *
 * @param message German, user-facing text, never a raw exception message (#514).
 * @param documentCount {@code null} when {@code reachable} is {@code false} - a failed probe never
 *     reports a count for the source it could not read.
 * @param credentialsVerified whether the probe got as far as checking the credentials, {@code null}
 *     for a connector that does not tell
 * @param details the connector's own findings, {@code null} for none (ADR-0038)
 */
public record SourceConnectionTestResult(
    boolean reachable,
    String message,
    Long documentCount,
    Boolean credentialsVerified,
    ConnectorData details) {

  public SourceConnectionTestResult(boolean reachable, String message, Long documentCount) {
    this(reachable, message, documentCount, null, null);
  }

  public SourceConnectionTestResult {
    if (!reachable && documentCount != null) {
      throw new IllegalArgumentException("documentCount must be null when reachable is false");
    }
  }
}
