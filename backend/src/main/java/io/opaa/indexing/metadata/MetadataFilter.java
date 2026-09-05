package io.opaa.indexing.metadata;

import io.opaa.api.types.DatePrecision;
import io.opaa.common.ValidationException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A filter on the filterable schema fields (metadata-schema.md Wirkstelle 1): a set of Dokumentart
 * codes, an inclusive Datum/Stand window and conditions on library fields. The title is not
 * filterable and free keywords never are - so these three are the whole vocabulary of a metadata
 * filter. {@link #NONE} is the absence of any condition.
 *
 * <p><b>Date semantics.</b> A stored value covers the whole span its {@link DatePrecision} leaves
 * open (YEAR 2024 is 2024-01-01..2024-12-31) and matches when that span overlaps the window. Since
 * a value is stored as the first day of its span, that is {@code value <= to} and {@code value >=
 * lowerBound(precision)} with {@link #dateFromBound} - the same two comparisons in both search
 * paths.
 *
 * @param documentTypes vocabulary codes; empty means no condition on the Dokumentart.
 * @param documentDateFrom inclusive window start, {@code null} for an open start.
 * @param documentDateTo inclusive window end, {@code null} for an open end.
 * @param libraryFields conditions on library fields, each naming its own library - the third and
 *     last kind of condition a filter can carry; free keywords never filter.
 */
public record MetadataFilter(
    Set<String> documentTypes,
    LocalDate documentDateFrom,
    LocalDate documentDateTo,
    List<LibraryFieldCondition> libraryFields) {

  public static final MetadataFilter NONE = new MetadataFilter(Set.of(), null, null, List.of());

  public MetadataFilter {
    documentTypes = documentTypes == null ? Set.of() : Set.copyOf(documentTypes);
    libraryFields =
        libraryFields == null
            ? List.of()
            : libraryFields.stream().filter(condition -> !condition.isEmpty()).toList();
    // One condition per field: two of them would be AND-ed and could only ever contradict each
    // other, which reads to the asking person like "the filter found nothing".
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (LibraryFieldCondition condition : libraryFields) {
      if (!seen.add(condition.libraryId() + "/" + condition.fieldKey())) {
        throw new ValidationException(
            "Für das Feld " + condition.fieldKey() + " steht mehr als eine Bedingung im Filter");
      }
    }
    if (documentDateFrom != null
        && documentDateTo != null
        && documentDateTo.isBefore(documentDateFrom)) {
      throw new ValidationException("Das Datumsfenster endet vor seinem Beginn");
    }
  }

  /** The core-field half alone - the shape every caller before built. */
  public MetadataFilter(
      Set<String> documentTypes, LocalDate documentDateFrom, LocalDate documentDateTo) {
    this(documentTypes, documentDateFrom, documentDateTo, List.of());
  }

  /** The same filter with {@code conditions} on library fields added. */
  public MetadataFilter withLibraryFields(List<LibraryFieldCondition> conditions) {
    return new MetadataFilter(documentTypes, documentDateFrom, documentDateTo, conditions);
  }

  /** A filter on the Dokumentart alone. */
  public static MetadataFilter ofDocumentTypes(Collection<String> codes) {
    return new MetadataFilter(new LinkedHashSet<>(codes), null, null);
  }

  /** A filter on the Datum/Stand window alone. */
  public static MetadataFilter ofDateWindow(LocalDate from, LocalDate to) {
    return new MetadataFilter(Set.of(), from, to);
  }

  /**
   * Parses the API shape - codes and ISO calendar dates - into a filter, rejecting an impossible
   * date with a {@link ValidationException} (400) rather than mapping it to a nearby day.
   */
  public static MetadataFilter parse(Collection<String> codes, String from, String to) {
    return new MetadataFilter(
        codes == null ? Set.of() : new LinkedHashSet<>(codes), parseDate(from), parseDate(to));
  }

  private static LocalDate parseDate(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(value.strip());
    } catch (DateTimeParseException e) {
      throw new ValidationException("Ungültiges Datum im Metadatenfilter: " + value);
    }
  }

  /** A filter without any condition - a no-op in both search paths. */
  public boolean isEmpty() {
    return documentTypes.isEmpty()
        && documentDateFrom == null
        && documentDateTo == null
        && libraryFields.isEmpty();
  }

  public boolean filtersLibraryFields() {
    return !libraryFields.isEmpty();
  }

  public boolean filtersDocumentType() {
    return !documentTypes.isEmpty();
  }

  public boolean filtersDocumentDate() {
    return documentDateFrom != null || documentDateTo != null;
  }

  /**
   * The same filter with every code checked against {@code vocabulary} - a code outside it is a
   * {@link ValidationException}: nothing is mapped to the nearest value, and a filter on a value no
   * document can carry would silently match only the documents without one.
   */
  public MetadataFilter validatedAgainst(DocumentTypeVocabulary vocabulary) {
    for (String code : documentTypes) {
      if (!vocabulary.containsCode(code)) {
        throw new ValidationException("Unbekannte Dokumentart im Metadatenfilter: " + code);
      }
    }
    return this;
  }

  /**
   * The lower bound a stored value of {@code precision} must reach so that its span still overlaps
   * a window starting at {@link #documentDateFrom}: the first day of the month or year the window
   * starts in, or the day itself for {@link DatePrecision#DAY}.
   */
  public LocalDate dateFromBound(DatePrecision precision) {
    if (documentDateFrom == null) {
      return null;
    }
    return switch (precision) {
      case DAY -> documentDateFrom;
      case MONTH -> documentDateFrom.withDayOfMonth(1);
      case YEAR -> documentDateFrom.withDayOfYear(1);
    };
  }

  /**
   * Whether a document with these values qualifies under this filter, including the Leerwert rule:
   * a missing value never disqualifies. The in-memory twin of the two query conditions, used to
   * mark hits as matched or "ohne Angabe" and to assert both paths against the same truth.
   */
  public boolean matches(String documentTypeCode, LocalDate documentDate, DatePrecision precision) {
    if (filtersDocumentType()
        && documentTypeCode != null
        && !documentTypes.contains(documentTypeCode)) {
      return false;
    }
    if (filtersDocumentDate() && documentDate != null) {
      DatePrecision effective = precision == null ? DatePrecision.DAY : precision;
      if (documentDateTo != null && documentDate.isAfter(documentDateTo)) {
        return false;
      }
      LocalDate lower = dateFromBound(effective);
      return lower == null || !documentDate.isBefore(lower);
    }
    return true;
  }

  /**
   * Whether a document with these values is kept only by the Leerwert rule - at least one filtered
   * field has no value on it.
   */
  public boolean keptWithoutValue(String documentTypeCode, LocalDate documentDate) {
    return (filtersDocumentType() && documentTypeCode == null)
        || (filtersDocumentDate() && documentDate == null);
  }
}
