package io.opaa.indexing.metadata;

import java.util.List;

/**
 * What one Charge of a library's pending schema changes did and what is left (metadata-schema.md,
 * "Nachlauf im Betrieb"): verarbeitet, ausstehend, fehlgeschlagen. A skipped document is unchanged
 * and stays pending - nothing is destroyed before its replacement exists.
 */
public record LibraryMetadataSchemaRunResult(
    long processedDocuments,
    long skippedDocuments,
    List<LibraryMetadataSchemaChangeView> pendingChanges) {

  public static LibraryMetadataSchemaRunResult nothingToDo() {
    return new LibraryMetadataSchemaRunResult(0, 0, List.of());
  }

  /** No pending change left - the signal to stop calling. */
  public boolean complete() {
    return pendingChanges.isEmpty();
  }

  public long remainingDocuments() {
    return pendingChanges.stream()
        .mapToLong(LibraryMetadataSchemaChangeView::remainingDocuments)
        .sum();
  }
}
