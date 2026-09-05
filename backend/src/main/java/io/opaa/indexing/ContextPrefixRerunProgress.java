package io.opaa.indexing;

import java.util.UUID;

/**
 * The Kontextpraefix state of one library (metadata-schema.md, "Nachlauf im Betrieb"), computed at
 * query time over its {@code INDEXED} documents. The Mischzustand it describes - part of the
 * bestand on the new prefix, the rest on the old - is a defined, permitted operating state, not a
 * fault: the search stays available over both halves.
 *
 * @param pendingDocuments documents never embedded with a prefix version or embedded under an older
 *     one, including the ones a manual correction of a prefix-effective value handed back
 * @param lastSkippedDocuments what the most recent Nachlauf call for this library could not advance
 *     - a process-lifetime figure (ADR-0021), 0 before the first call
 */
public record ContextPrefixRerunProgress(
    UUID libraryId,
    int prefixVersion,
    long totalDocuments,
    long currentDocuments,
    long pendingDocuments,
    long lastSkippedDocuments) {

  public static ContextPrefixRerunProgress empty(UUID libraryId, int prefixVersion) {
    return new ContextPrefixRerunProgress(libraryId, prefixVersion, 0, 0, 0, 0);
  }

  public boolean isComplete() {
    return pendingDocuments == 0;
  }
}
