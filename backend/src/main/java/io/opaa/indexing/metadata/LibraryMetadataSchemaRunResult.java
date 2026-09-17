package io.opaa.indexing.metadata;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What one Charge of a library's pending schema changes did and what is left (metadata-schema.md,
 * "Nachlauf im Betrieb"): verarbeitet, ausstehend, fehlgeschlagen. A skipped document is unchanged
 * and stays pending - nothing is destroyed before its replacement exists.
 */
public record LibraryMetadataSchemaRunResult(
    long processedDocuments,
    long skippedDocuments,
    List<LibraryMetadataSchemaChangeView> pendingChanges,
    UUID confirmedChangeId) {

  public static LibraryMetadataSchemaRunResult nothingToDo() {
    return new LibraryMetadataSchemaRunResult(0, 0, List.of(), null);
  }

  /**
   * The state of the one change this call confirmed - a library may well have another one running,
   * and that one's remaining work is none of this answer's business.
   */
  public Optional<LibraryMetadataSchemaChangeView> confirmedChange() {
    return confirmedChangeId == null
        ? Optional.empty()
        : pendingChanges.stream()
            .filter(change -> confirmedChangeId.equals(change.id()))
            .findFirst();
  }

  /** Whether the confirmed change is done; without one, whether the library has none left. */
  public boolean confirmedChangeComplete() {
    return confirmedChangeId == null ? complete() : confirmedChange().isEmpty();
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
