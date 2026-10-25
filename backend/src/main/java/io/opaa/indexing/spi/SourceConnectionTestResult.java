package io.opaa.indexing.spi;

/**
 * The outcome of a {@link SourceConnector#testConnection} probe - the domain counterpart of the
 * generated {@code SourceConnectionTestResponse}.
 *
 * @param message German, user-facing text, never a raw exception message.
 * @param documentCount {@code null} when {@code reachable} is {@code false} - a failed probe never
 *     reports a count for the source it could not read.
 * @param credentialsVerified {@code false} when the source rejected the secret or none was usable
 *     as given (missing, malformed); {@code true} once it accepted it, also with a permission,
 *     scope or consent missing; {@code null} when the probe did not get that far (blocked target,
 *     TLS, network, clock, server error) or does not tell.
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
