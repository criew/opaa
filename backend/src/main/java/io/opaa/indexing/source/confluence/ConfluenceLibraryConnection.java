package io.opaa.indexing.source.confluence;

import io.opaa.indexing.source.SourceSettings;
import io.opaa.sourceaccess.ProxyAndCredentials;
import java.net.URI;

/**
 * Assembles the {@link ConfluenceConnection} of a CONFLUENCE library from the source settings the
 * core resolved for its run - the run-side counterpart of what {@code ConfluenceConnectionService}
 * does for the connection test with raw request fields. Every defect in the configuration surfaces
 * as one German sentence a run can fail with.
 */
final class ConfluenceLibraryConnection {

  private ConfluenceLibraryConnection() {}

  static ConfluenceConnection of(SourceSettings settings, String credentials) {
    ConfluenceEdition edition =
        ConfluenceSourceSettings.stored(settings.connectorSettings()).edition();
    if (edition == null) {
      throw new InvalidConfluenceConfigurationException(
          "Die Bibliothek trägt keine Confluence-Edition; bitte die Quellkonfiguration prüfen.");
    }
    ProxyAndCredentials proxy;
    try {
      proxy = ProxyAndCredentials.parse(settings.sourceProxy(), null);
    } catch (ProxyAndCredentials.InvalidProxyConfigurationException e) {
      throw new InvalidConfluenceConfigurationException(e.getMessage());
    }
    URI baseUrl;
    ConfluenceCredentials parsed;
    try {
      baseUrl = ConfluenceConnection.normalizeBaseUrl(settings.sourceUrl(), edition);
      parsed = ConfluenceCredentials.parse(edition, credentials);
    } catch (ConfluenceConnection.InvalidBaseUrlException
        | ConfluenceCredentials.InvalidCredentialsFormatException e) {
      throw new InvalidConfluenceConfigurationException(e.getMessage());
    }
    return new ConfluenceConnection(
        baseUrl,
        edition,
        parsed,
        proxy.proxyHost(),
        proxy.proxyPort(),
        settings.sourceInsecureSsl());
  }

  /** The configuration cannot be turned into a connection; the message is user-facing. */
  static final class InvalidConfluenceConfigurationException extends RuntimeException {
    InvalidConfluenceConfigurationException(String message) {
      super(message);
    }
  }
}
