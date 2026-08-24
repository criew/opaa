package io.opaa.library;

import io.opaa.indexing.DocumentSourceType;
import java.net.URI;
import java.util.UUID;

/**
 * Parameters for {@link SourceConnectionTestService#test(SourceConnectionTest, UUID, boolean)} -
 * replaces the generated {@code SourceConnectionTestRequest} at the service boundary (#860), see
 * AGENTS.md "API & DTO-Konvention".
 *
 * @param libraryId {@code null} for a standalone test (#514); set to test an existing library's
 *     stored quellkonfiguration without resending a credential the caller does not know (#544).
 */
public record SourceConnectionTest(
    DocumentSourceType sourceType,
    String sourcePath,
    URI sourceUrl,
    String sourceProxy,
    String sourceCredentials,
    Boolean sourceInsecureSsl,
    UUID libraryId) {}
