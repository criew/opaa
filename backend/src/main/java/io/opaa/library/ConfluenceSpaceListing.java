package io.opaa.library;

import io.opaa.api.types.ConfluenceEdition;
import java.net.URI;
import java.util.UUID;

/**
 * Domain counterpart of {@code ConfluenceSpaceListRequest} (#1134): what a caller hands in to list
 * the spaces a Confluence token may read. {@code libraryId} names an existing library whose stored
 * credentials may stand in for omitted ones (same-origin rule, see {@link
 * SourceConnectionTestService}).
 */
public record ConfluenceSpaceListing(
    URI sourceUrl,
    ConfluenceEdition confluenceEdition,
    String sourceCredentials,
    String sourceProxy,
    Boolean sourceInsecureSsl,
    UUID libraryId) {}
