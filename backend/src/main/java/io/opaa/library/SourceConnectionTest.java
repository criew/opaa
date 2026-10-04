package io.opaa.library;

import io.opaa.auth.CurrentUser;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.knowledge.SourceType;
import java.net.URI;
import java.util.UUID;

/**
 * Parameters for {@link SourceConnectionTestService#test(SourceConnectionTest, CurrentUser)} -
 * replaces the generated {@code SourceConnectionTestRequest} at the service boundary (#860), see
 * AGENTS.md "API &amp; DTO-Konvention".
 *
 * @param libraryId {@code null} for a standalone test (#514); set to test an existing library's
 *     stored quellkonfiguration without resending a credential the caller does not know (#544).
 * @param connectorSettings the connector settings the probe uses (ADR-0038), {@code null} for none
 *     - with {@code libraryId} the connector decides whether the stored ones stand in
 * @param connectionProfileId the profile the probe runs through; {@code null} for the library's
 *     current one, or without {@code libraryId} for its own address
 */
public record SourceConnectionTest(
    SourceType sourceType,
    String sourcePath,
    URI sourceUrl,
    String sourceProxy,
    String sourceCredentials,
    Boolean sourceInsecureSsl,
    UUID libraryId,
    ConnectorData connectorSettings,
    UUID connectionProfileId) {

  /** A test with its own address. */
  public SourceConnectionTest(
      SourceType sourceType,
      String sourcePath,
      URI sourceUrl,
      String sourceProxy,
      String sourceCredentials,
      Boolean sourceInsecureSsl,
      UUID libraryId,
      ConnectorData connectorSettings) {
    this(
        sourceType,
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl,
        libraryId,
        connectorSettings,
        null);
  }
}
