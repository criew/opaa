package io.opaa.indexing.source.s3;

import io.opaa.library.KnowledgeLibrary;
import io.opaa.sourceaccess.ProxyAndCredentials;
import java.net.URI;

/**
 * Assembles the {@link S3Connection} of an S3 library from its stored source configuration
 * (ADR-0018: the library is the only configuration) - the run-side counterpart of what {@code
 * S3ConnectionService} does for the connection test with raw request fields. Every defect in the
 * stored configuration surfaces as one German sentence a run can fail with; the credentials never
 * appear in it.
 */
final class S3LibraryConnection {

  static final String NO_SETTINGS =
      "Die Bibliothek trägt keine S3-Konfiguration; bitte die Quellkonfiguration prüfen.";

  private S3LibraryConnection() {}

  /**
   * @param settings the library's own {@link KnowledgeLibrary#getS3Settings()}, passed in so the
   *     caller reads it once
   */
  static S3Connection of(KnowledgeLibrary library, S3SourceSettings settings) {
    if (settings == null) {
      throw new InvalidS3ConfigurationException(NO_SETTINGS);
    }
    URI endpoint;
    S3Credentials credentials;
    ProxyAndCredentials proxy;
    try {
      endpoint = S3Connection.normalizeEndpoint(library.getSourceUrl());
      credentials = S3Credentials.parse(library.getSourceCredentials());
      proxy = ProxyAndCredentials.parse(library.getSourceProxy(), null);
    } catch (S3Connection.InvalidEndpointException
        | S3Credentials.InvalidCredentialsFormatException
        | ProxyAndCredentials.InvalidProxyConfigurationException e) {
      throw new InvalidS3ConfigurationException(e.getMessage());
    }
    return new S3Connection(
        endpoint,
        settings.effectiveRegion(),
        settings.pathStyle(),
        credentials,
        proxy.proxyHost(),
        proxy.proxyPort(),
        library.isSourceInsecureSsl());
  }

  /** The stored configuration cannot be turned into a connection; the message is user-facing. */
  static final class InvalidS3ConfigurationException extends RuntimeException {
    InvalidS3ConfigurationException(String message) {
      super(message);
    }
  }
}
