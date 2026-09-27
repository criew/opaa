package io.opaa.library;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.indexing.source.ConnectorData;
import java.net.URI;
import java.util.UUID;

/**
 * What a caller hands in to list what a source of {@code sourceType} offers for selection before
 * the configuration is saved. {@code libraryId} names an existing library whose stored credentials
 * may stand in for omitted ones (same-origin rule, see {@link SourceConnectionTestService}).
 *
 * @param query the listing's own parameters as the type's connector reads them, {@code null} for
 *     none
 */
public record SourceBrowseRequest(
    DocumentSourceType sourceType,
    URI sourceUrl,
    String sourceCredentials,
    String sourceProxy,
    Boolean sourceInsecureSsl,
    ConnectorData query,
    UUID libraryId) {}
