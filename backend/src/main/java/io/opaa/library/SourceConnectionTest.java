package io.opaa.library;

import io.opaa.api.types.ConfluenceEdition;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.indexing.source.s3.S3SourceSettings;
import java.net.URI;
import java.util.UUID;

/**
 * Parameters for {@link SourceConnectionTestService#test(SourceConnectionTest, UUID, boolean)} -
 * replaces the generated {@code SourceConnectionTestRequest} at the service boundary (#860), see
 * AGENTS.md "API & DTO-Konvention".
 *
 * @param libraryId {@code null} for a standalone test (#514); set to test an existing library's
 *     stored quellkonfiguration without resending a credential the caller does not know (#544).
 * @param s3Settings the scopes an {@code S3} test probes (ADR-0027, #1376); with {@code libraryId}
 *     the library's stored settings stand in for an omitted value
 */
public record SourceConnectionTest(
    DocumentSourceType sourceType,
    String sourcePath,
    URI sourceUrl,
    String sourceProxy,
    String sourceCredentials,
    Boolean sourceInsecureSsl,
    UUID libraryId,
    ConfluenceEdition confluenceEdition,
    S3SourceSettings s3Settings) {}
