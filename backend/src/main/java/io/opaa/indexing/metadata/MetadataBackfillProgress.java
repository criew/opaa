package io.opaa.indexing.metadata;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * The core-metadata extraction state of one library (metadata-schema.md, "Nachlauf im Betrieb"),
 * computed at query time over its {@code INDEXED} documents: how many carry the current {@link
 * CoreMetadataExtractor#EXTRACTION_VERSION}, how many are still pending, and the Füllgrad per core
 * field. A pending document is a defined, permitted state - the search keeps running over it with
 * its old fields.
 *
 * @param lastSkippedDocuments what the most recent backfill call for this library could not advance
 *     - a process-lifetime figure (ADR-0021), 0 before the first call
 * @param filledDocumentsByField indexed documents carrying a {@code SET} value, per core field
 */
public record MetadataBackfillProgress(
    UUID libraryId,
    long totalDocuments,
    long currentDocuments,
    long pendingDocuments,
    long lastSkippedDocuments,
    Map<CoreMetadataField, Long> filledDocumentsByField) {

  public MetadataBackfillProgress {
    Map<CoreMetadataField, Long> complete = new EnumMap<>(CoreMetadataField.class);
    for (CoreMetadataField field : CoreMetadataField.values()) {
      complete.put(field, filledDocumentsByField.getOrDefault(field, 0L));
    }
    filledDocumentsByField = Map.copyOf(complete);
  }

  public static MetadataBackfillProgress empty(UUID libraryId) {
    return new MetadataBackfillProgress(libraryId, 0, 0, 0, 0, Map.of());
  }

  public boolean isComplete() {
    return pendingDocuments == 0;
  }

  /** {@code filled / total} in 0..1; 0 for a library without indexed documents. */
  public double filledShare(CoreMetadataField field) {
    if (totalDocuments == 0) {
      return 0d;
    }
    return (double) filledDocumentsByField.get(field) / totalDocuments;
  }
}
