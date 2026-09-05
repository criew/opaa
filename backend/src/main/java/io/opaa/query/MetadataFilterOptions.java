package io.opaa.query;

import io.opaa.api.types.LibraryMetadataFieldType;
import io.opaa.indexing.metadata.CoreMetadataField;
import io.opaa.indexing.metadata.FormatMetadataField;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What the filter interface needs for one person and one search scope: the Füllstand per filterable
 * core field with the entry condition applied, the Dokumentart values occurring in that scope and
 * the span of its Datum/Stand values. Built in the rights context of the asking person over the
 * libraries the next question would search - never a global aggregate (metadata-schema.md,
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
    LocalDate documentDateMax,
    List<LibraryFieldOption> libraryFields,
    List<FormatFieldOption> formatFields) {

  public MetadataFilterOptions {
    fields = List.copyOf(fields);
    documentTypes = List.copyOf(documentTypes);
    libraryFields = libraryFields == null ? List.of() : List.copyOf(libraryFields);
    formatFields = formatFields == null ? List.of() : List.copyOf(formatFields);
  }

  /** The shape without format field options - a scope whose documents carry none. */
  public MetadataFilterOptions(
      long totalDocuments,
      List<FieldOption> fields,
      List<DocumentTypeOption> documentTypes,
      LocalDate documentDateMin,
      LocalDate documentDateMax,
      List<LibraryFieldOption> libraryFields) {
    this(
        totalDocuments,
        fields,
        documentTypes,
        documentDateMin,
        documentDateMax,
        libraryFields,
        List.of());
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

  /**
   * One filterable library field of the scope, with its own Füllstand and the values its documents
   * actually carry. The base of {@link LibraryFieldOption#fillShare()} is the field's own library,
   * not the whole scope: the field exists only there, and measuring it against libraries that
   * cannot carry it would keep a well-maintained field of a small library permanently below the
   * threshold. The offered values are an aggregate over documents and are therefore built in the
   * rights context of the asking person - unlike the configured list, which is schema.
   */
  public record LibraryFieldOption(
      UUID libraryId,
      String libraryName,
      String fieldKey,
      String label,
      LibraryMetadataFieldType type,
      long filledDocuments,
      long totalDocuments,
      double threshold,
      List<LibraryFieldValueOption> values,
      LocalDate dateMin,
      LocalDate dateMax) {

    public LibraryFieldOption {
      values = List.copyOf(values);
    }

    public double fillShare() {
      return totalDocuments == 0 ? 0d : (double) filledDocuments / totalDocuments;
    }

    /** A field below the threshold - or any field of an empty library - is not offered. */
    public boolean offered() {
      return totalDocuments > 0 && fillShare() >= threshold;
    }
  }

  /** One value of a library field that occurs in the scope, with how many documents carry it. */
  public record LibraryFieldValueOption(String code, String label, long documentCount) {}

  /**
   * One filterable format field with the values the scope's documents actually carry. It is offered
   * as soon as one document carries a value: a format field is filled deterministically for every
   * document of its format and structurally absent for every other, so a share over a mixed bestand
   * would measure the format mix rather than the metadata quality. The counts stay in the response
   * so a person sees how large the filterable part of the scope is.
   */
  public record FormatFieldOption(
      FormatMetadataField field,
      long filledDocuments,
      long totalDocuments,
      List<LibraryFieldValueOption> values,
      boolean valuesCapped) {

    /**
     * How many values of an open-ended field the options ever carry. A postbox has as many senders
     * as it has correspondents; a complete list would be an unbounded response of personal
     * addresses, a cache entry per person and a popover nobody can read. The interface offers a
     * free input for an exact value beside the list.
     */
    public static final int MAX_OFFERED_VALUES = 20;

    public FormatFieldOption {
      values = List.copyOf(values);
    }

    /** Offered as soon as one document of the scope carries a value. */
    public boolean offered() {
      return filledDocuments > 0;
    }
  }
}
