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
 * <p>The per-field figures are {@link MetadataFieldFill}s, the same record the Pflege-Anker of the
 * library's own settings carries (#1305) - one definition of "ohne Wert", two rights contexts.
 *
 * @param pendingDocuments every indexed document below the current version - including the ones a
 *     backfill call no longer selects because they wait for their connector run
 * @param awaitingConnectorRunDocuments the subset of {@code pendingDocuments} whose bytes live on a
 *     remote and whose change markers are cleared: only the next connector run can advance them, no
 *     further backfill call will
 * @param lastSkippedDocuments what the most recent backfill call for this library could not advance
 *     - a process-lifetime figure (ADR-0021), 0 before the first call
 * @param fillByField the fill of every core field over this library's indexed documents
 */
public record MetadataBackfillProgress(
    UUID libraryId,
    long totalDocuments,
    long currentDocuments,
    long pendingDocuments,
    long awaitingConnectorRunDocuments,
    long lastSkippedDocuments,
    Map<CoreMetadataField, MetadataFieldFill> fillByField) {

  public MetadataBackfillProgress {
    Map<CoreMetadataField, MetadataFieldFill> complete = new EnumMap<>(CoreMetadataField.class);
    for (CoreMetadataField field : CoreMetadataField.values()) {
      complete.put(
          field,
          fillByField == null
              ? MetadataFieldFill.EMPTY.withTotal(totalDocuments)
              : fillByField.getOrDefault(field, MetadataFieldFill.EMPTY).withTotal(totalDocuments));
    }
    fillByField = Map.copyOf(complete);
  }

  public static MetadataBackfillProgress empty(UUID libraryId) {
    return new MetadataBackfillProgress(libraryId, 0, 0, 0, 0, 0, Map.of());
  }

  public boolean isComplete() {
    return pendingDocuments == 0;
  }

  public MetadataFieldFill fillOf(CoreMetadataField field) {
    return fillByField.get(field);
  }

  /** {@code filled / total} in 0..1; 0 for a library without indexed documents. */
  public double filledShare(CoreMetadataField field) {
    return fillOf(field).filledShare();
  }

  public long filledDocuments(CoreMetadataField field) {
    return fillOf(field).filledDocuments();
  }

  public long notDeterminableDocuments(CoreMetadataField field) {
    return fillOf(field).notDeterminableDocuments();
  }

  /** The Pflege-Anker of this library and field (#1069), counted in the administrative context. */
  public long documentsWithoutValue(CoreMetadataField field) {
    return fillOf(field).documentsWithoutValue();
  }

  /** {@code documentsWithoutValue / total} in 0..1; 0 for a library without indexed documents. */
  public double missingShare(CoreMetadataField field) {
    return fillOf(field).missingShare();
  }
}
