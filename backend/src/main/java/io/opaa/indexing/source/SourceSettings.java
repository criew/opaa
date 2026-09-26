package io.opaa.indexing.source;

/**
 * A library's source configuration as a {@link SourceConnector} sees it: the connection fields
 * every run-based type draws from (path, address, proxy, credentials, TLS switch) and the
 * connector-owned settings (ADR-0038). {@code connectorSettings} is {@code null} when absent - on a
 * change that means "leave the stored ones as they are".
 */
public record SourceSettings(
    String sourcePath,
    String sourceUrl,
    String sourceProxy,
    String sourceCredentials,
    boolean sourceInsecureSsl,
    ConnectorData connectorSettings) {

  /** Names whether credentials are set, never their value - the record may reach a log. */
  @Override
  public String toString() {
    return "SourceSettings[sourcePath="
        + sourcePath
        + ", sourceUrl="
        + sourceUrl
        + ", sourceProxy="
        + sourceProxy
        + ", sourceCredentials="
        + (sourceCredentials == null ? "null" : "***")
        + ", sourceInsecureSsl="
        + sourceInsecureSsl
        + ", connectorSettings="
        + connectorSettings
        + "]";
  }

  /** A copy carrying {@code url} as its address - the normalised form a connector stores. */
  public SourceSettings withSourceUrl(String url) {
    return new SourceSettings(
        sourcePath, url, sourceProxy, sourceCredentials, sourceInsecureSsl, connectorSettings);
  }

  /** A copy carrying {@code settings} as the connector-owned part. */
  public SourceSettings withConnectorSettings(ConnectorData settings) {
    return new SourceSettings(
        sourcePath, sourceUrl, sourceProxy, sourceCredentials, sourceInsecureSsl, settings);
  }
}
