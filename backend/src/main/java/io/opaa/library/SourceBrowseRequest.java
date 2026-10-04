package io.opaa.library;

import io.opaa.indexing.source.ConnectorData;
import io.opaa.knowledge.SourceType;
import java.net.URI;
import java.util.UUID;

/**
 * What a caller hands in to list what a source of {@code sourceType} offers for selection before
 * the configuration is saved. {@code libraryId} names an existing library whose stored credentials
 * may stand in for omitted ones (same-origin rule, see {@link SourceConnectionTestService}).
 *
 * @param query the listing's own parameters as the type's connector reads them, {@code null} for
 *     none
 * @param connectionProfileId the profile the probe runs through; {@code null} for the library's
 *     current one, or without {@code libraryId} for its own address
 * @param privateLibrary as {@link SourceConnectionTest#privateLibrary}
 */
public record SourceBrowseRequest(
    SourceType sourceType,
    URI sourceUrl,
    String sourceCredentials,
    String sourceProxy,
    Boolean sourceInsecureSsl,
    ConnectorData query,
    UUID libraryId,
    UUID connectionProfileId,
    boolean privateLibrary) {

  /** A listing towards a shared library. */
  public SourceBrowseRequest(
      SourceType sourceType,
      URI sourceUrl,
      String sourceCredentials,
      String sourceProxy,
      Boolean sourceInsecureSsl,
      ConnectorData query,
      UUID libraryId,
      UUID connectionProfileId) {
    this(
        sourceType,
        sourceUrl,
        sourceCredentials,
        sourceProxy,
        sourceInsecureSsl,
        query,
        libraryId,
        connectionProfileId,
        false);
  }

  /** A request with its own address. */
  public SourceBrowseRequest(
      SourceType sourceType,
      URI sourceUrl,
      String sourceCredentials,
      String sourceProxy,
      Boolean sourceInsecureSsl,
      ConnectorData query,
      UUID libraryId) {
    this(
        sourceType,
        sourceUrl,
        sourceCredentials,
        sourceProxy,
        sourceInsecureSsl,
        query,
        libraryId,
        null);
  }
}
