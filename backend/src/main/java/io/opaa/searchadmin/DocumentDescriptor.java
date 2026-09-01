package io.opaa.searchadmin;

import java.util.UUID;

/**
 * The human-readable identity behind a chunk's document grouping key: file name and library. {@code
 * fileName}/{@code libraryId}/{@code libraryName} are {@code null} for a key that no longer
 * resolves to a document row - a chunk left over from a deleted document is still part of what the
 * run did, and hiding it would make the protocol lie about its own counts.
 */
public record DocumentDescriptor(
    String documentKey, String fileName, UUID libraryId, String libraryName) {}
