package io.opaa.indexing.source;

/**
 * A library's source configuration as a {@link SourceConnector} sees it: the connection fields
 * every run-based type draws from (path, address, proxy, credentials with their kind, TLS switch)
 * and the connector-owned settings (ADR-0038). {@code connectorSettings} is {@code null} when
 * absent - on a change that means "leave the stored ones as they are". {@code credentialsKind} is
 * {@code null} exactly when there are no credentials, and a personal secret unless named otherwise.
 */
public record SourceSettings(
    String sourcePath,
    String sourceUrl,
    String sourceProxy,
    String sourceCredentials,
    boolean sourceInsecureSsl,
    ConnectorData connectorSettings,
    SecretKind credentialsKind) {

  public SourceSettings {
    if (sourceCredentials == null) {
      credentialsKind = null;
    } else if (credentialsKind == null) {
      credentialsKind = SecretKind.PERSONAL_SECRET;
    }
  }

  /** Settings whose credentials, if any, are a personal secret. */
  public SourceSettings(
      String sourcePath,
      String sourceUrl,
      String sourceProxy,
      String sourceCredentials,
      boolean sourceInsecureSsl,
      ConnectorData connectorSettings) {
    this(
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl,
        connectorSettings,
        null);
  }

  /** The credentials with their kind, {@code null} for none. */
  public Secret credentials() {
    return sourceCredentials == null ? null : new Secret(credentialsKind, sourceCredentials);
  }

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
        + ", credentialsKind="
        + credentialsKind
        + "]";
  }

  /** A copy carrying {@code url} as its address - the normalised form a connector stores. */
  public SourceSettings withSourceUrl(String url) {
    return new SourceSettings(
        sourcePath,
        url,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl,
        connectorSettings,
        credentialsKind);
  }

  /** A copy carrying {@code credentials} as its secret, a personal one. */
  public SourceSettings withSourceCredentials(String credentials) {
    return withCredentials(Secret.personal(credentials));
  }

  /** A copy carrying {@code secret}, {@code null} for none. */
  public SourceSettings withCredentials(Secret secret) {
    return new SourceSettings(
        sourcePath,
        sourceUrl,
        sourceProxy,
        Secret.valueOf(secret),
        sourceInsecureSsl,
        connectorSettings,
        secret == null ? null : secret.kind());
  }

  /** A copy without the secret. */
  public SourceSettings withoutCredentials() {
    return withCredentials(null);
  }

  /** A copy carrying {@code settings} as the connector-owned part. */
  public SourceSettings withConnectorSettings(ConnectorData settings) {
    return new SourceSettings(
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl,
        settings,
        credentialsKind);
  }
}
