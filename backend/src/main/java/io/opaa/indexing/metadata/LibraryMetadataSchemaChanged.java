package io.opaa.indexing.metadata;

import java.util.UUID;

/**
 * A library's metadata field schema changed (#1071): a field was defined, altered or removed, or a
 * value list was extended or remapped. Published after the change commits; the filter options of
 * every person whose search scope contains the library are derived from this schema and must be
 * recomputed - otherwise the filter interface offers a value that no longer exists (and rejects it
 * with 400 when chosen) or hides a field the manager just created.
 */
public record LibraryMetadataSchemaChanged(UUID libraryId) {}
