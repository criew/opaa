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
 * @param pendingDocuments every indexed document below the current version - including the ones a
 *     backfill call no longer selects because they wait for their connector run
 * @param awaitingConnectorRunDocuments the subset of {@code pendingDocuments} whose bytes live on a
 *     remote and whose change markers are cleared: only the next connector run can advance them, no
 *     further backfill call will
 * @param lastSkippedDocuments what the most recent backfill call for this library could not advance
 *     - a process-lifetime figure (ADR-0021), 0 before the first call
 * @param filledDocumentsByField indexed documents carrying a {@code SET} value, per core field
 * @param notDeterminableDocumentsByField indexed documents a person marked as "kein Wert
 *     ermittelbar", per core field - neither filled nor open
 */
public record MetadataBackfillProgress(
    UUID libraryId,
    long totalDocuments,
    long currentDocuments,
    long pendingDocuments,
    long awaitingConnectorRunDocuments,
    long lastSkippedDocuments,
    Map<CoreMetadataField, Long> filledDocumentsByField,
    Map<CoreMetadataField, Long> notDeterminableDocumentsByField) {

  public MetadataBackfillProgress {
    filledDocumentsByField = completed(filledDocumentsByField);
    notDeterminableDocumentsByField = completed(notDeterminableDocumentsByField);
  }

  private static Map<CoreMetadataField, Long> completed(Map<CoreMetadataField, Long> counts) {
    Map<CoreMetadataField, Long> complete = new EnumMap<>(CoreMetadataField.class);
    for (CoreMetadataField field : CoreMetadataField.values()) {
      complete.put(field, counts.getOrDefault(field, 0L));
    }
    return Map.copyOf(complete);
  }

  public static MetadataBackfillProgress empty(UUID libraryId) {
    return new MetadataBackfillProgress(libraryId, 0, 0, 0, 0, 0, Map.of(), Map.of());
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

  /**
   * The Pflege-Anker of this library and field: documents with neither a value nor a "kein Wert
   * ermittelbar" mark - the same definition {@link MetadataFieldMaintenance} uses in the library's
   * own settings, counted here in the administrative context.
   */
  public long documentsWithoutValue(CoreMetadataField field) {
    return Math.max(
        0,
        totalDocuments
            - filledDocumentsByField.get(field)
            - notDeterminableDocumentsByField.get(field));
  }

  /** {@code documentsWithoutValue / total} in 0..1; 0 for a library without indexed documents. */
  public double missingShare(CoreMetadataField field) {
    return totalDocuments == 0 ? 0d : (double) documentsWithoutValue(field) / totalDocuments;
  }
}
