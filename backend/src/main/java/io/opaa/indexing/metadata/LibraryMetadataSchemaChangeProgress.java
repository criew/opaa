package io.opaa.indexing.metadata;

import java.util.UUID;

/**
 * The schema-change state of one library for the index status page (metadata-schema.md, "Nachlauf
 * im Betrieb"). A library with a pending change carries a defined, permitted Mischzustand: every
 * document keeps a value the schema still lists until its turn comes, and the search stays
 * available throughout.
 *
 * @param lastSkippedDocuments what the most recent Charge could not advance - a process-lifetime
 *     figure (ADR-0021), 0 before the first call
 */
public record LibraryMetadataSchemaChangeProgress(
    UUID libraryId, long pendingChanges, long pendingDocuments, long lastSkippedDocuments) {

  public static LibraryMetadataSchemaChangeProgress empty(UUID libraryId) {
    return new LibraryMetadataSchemaChangeProgress(libraryId, 0, 0, 0);
  }
}
