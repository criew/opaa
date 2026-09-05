package io.opaa.indexing;

import java.util.UUID;

/**
 * The Kontextpräfix state of one library (metadata-schema.md, "Nachlauf im Betrieb"), computed at
 * query time over its {@code INDEXED} documents. The Mischzustand it describes - part of the
 * bestand on the new prefix, the rest on the old - is a defined, permitted operating state, not a
 * fault: the search stays available over both halves.
 *
 * @param pendingDocuments documents whose prefix stamp was cleared by a change to their own prefix,
 *     plus the bestand that never carried one
 * @param lastSkippedDocuments what the most recent Nachlauf call for this library could not advance
 *     - a process-lifetime figure (ADR-0021), 0 before the first call
 */
public record ContextPrefixRerunProgress(
    UUID libraryId,
    long totalDocuments,
    long currentDocuments,
    long pendingDocuments,
    long lastSkippedDocuments) {

  public static ContextPrefixRerunProgress empty(UUID libraryId) {
    return new ContextPrefixRerunProgress(libraryId, 0, 0, 0, 0);
  }

  public boolean isComplete() {
    return pendingDocuments == 0;
  }
}
