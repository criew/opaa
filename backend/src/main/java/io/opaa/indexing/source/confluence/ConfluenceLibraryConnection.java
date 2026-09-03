package io.opaa.indexing.source.confluence;

import io.opaa.api.types.ConfluenceEdition;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.sourceaccess.ProxyAndCredentials;
import java.net.URI;

/**
 * Assembles the {@link ConfluenceConnection} of a CONFLUENCE library from its stored source
 * configuration (ADR-0018: the library is the only configuration) - the run-side counterpart of
 * what {@code ConfluenceConnectionService} does for the connection test with raw request fields.
 * Every defect in the stored configuration surfaces as one German sentence a run can fail with.
 */
final class ConfluenceLibraryConnection {

  private ConfluenceLibraryConnection() {}

  static ConfluenceConnection of(KnowledgeLibrary library) {
    ConfluenceEdition edition = library.getSourceConfluenceEdition();
    if (edition == null) {
      throw new InvalidConfluenceConfigurationException(
          "Die Bibliothek trägt keine Confluence-Edition; bitte die Quellkonfiguration prüfen.");
    }
    ProxyAndCredentials proxy;
    try {
      proxy = ProxyAndCredentials.parse(library.getSourceProxy(), null);
    } catch (ProxyAndCredentials.InvalidProxyConfigurationException e) {
      throw new InvalidConfluenceConfigurationException(e.getMessage());
    }
    URI baseUrl;
    ConfluenceCredentials credentials;
    try {
      baseUrl = ConfluenceConnection.normalizeBaseUrl(library.getSourceUrl(), edition);
      credentials = ConfluenceCredentials.parse(edition, library.getSourceCredentials());
    } catch (ConfluenceConnection.InvalidBaseUrlException
        | ConfluenceCredentials.InvalidCredentialsFormatException e) {
      throw new InvalidConfluenceConfigurationException(e.getMessage());
    }
    return new ConfluenceConnection(
        baseUrl,
        edition,
        credentials,
        proxy.proxyHost(),
        proxy.proxyPort(),
        library.isSourceInsecureSsl());
  }

  /** The stored configuration cannot be turned into a connection; the message is user-facing. */
  static final class InvalidConfluenceConfigurationException extends RuntimeException {
    InvalidConfluenceConfigurationException(String message) {
      super(message);
    }
  }
}
