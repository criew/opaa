package io.opaa.query;

import io.opaa.indexing.metadata.CoreMetadataField;
import java.time.LocalDate;
import java.util.List;

/**
 * What the filter interface needs for one person and one search scope (#1070): the Füllstand per
 * filterable core field with the entry condition applied, the Dokumentart values occurring in that
 * scope and the span of its Datum/Stand values. Built in the rights context of the asking person
 * over the libraries the next question would search - never a global aggregate (metadata-schema.md,
 * Rechte-Invariante).
 *
 * @param totalDocuments indexed documents of the scope, the base of every share.
 * @param fields the two filterable fields in schema order.
 * @param documentTypes the Dokumentart values at least one document of the scope carries.
 * @param documentDateMin earliest Datum/Stand value in the scope, {@code null} without any.
 * @param documentDateMax latest, {@code null} without any.
 */
public record MetadataFilterOptions(
    long totalDocuments,
    List<FieldOption> fields,
    List<DocumentTypeOption> documentTypes,
    LocalDate documentDateMin,
    LocalDate documentDateMax) {

  public MetadataFilterOptions {
    fields = List.copyOf(fields);
    documentTypes = List.copyOf(documentTypes);
  }

  /**
   * One field's Füllstand: documents with a value or the mark "kein Wert ermittelbar" over the
   * scope's indexed documents, and whether that reaches the committed threshold.
   */
  public record FieldOption(
      CoreMetadataField field, long filledDocuments, long totalDocuments, double threshold) {

    public double fillShare() {
      return totalDocuments == 0 ? 0d : (double) filledDocuments / totalDocuments;
    }

    /** A field below the threshold - or any field of an empty scope - is not offered. */
    public boolean offered() {
      return totalDocuments > 0 && fillShare() >= threshold;
    }
  }

  /** One Dokumentart that occurs in the scope, with how many documents carry it. */
  public record DocumentTypeOption(String code, String label, long documentCount) {}
}
