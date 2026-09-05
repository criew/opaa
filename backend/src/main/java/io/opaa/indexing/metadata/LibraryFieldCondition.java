package io.opaa.indexing.metadata;

import io.opaa.api.types.DatePrecision;
import io.opaa.api.types.LibraryMetadataFieldType;
import io.opaa.common.ValidationException;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * One condition of a {@link MetadataFilter} on a library field. The field identity is the pair
 * {@code (libraryId, fieldKey)}, never the key alone: two libraries may each define {@code fassung}
 * with their own value lists, and those are two fields - a condition that named only the key would
 * silently filter a foreign library's documents by a list they were never checked against.
 *
 * <p>Exactly one shape is populated, following the field's type: {@code codes} for SELECT, the
 * inclusive window for DATE (identical span semantics to the Kernfeld Datum/Stand, see {@link
 * MetadataFilter}), and {@code value} for PATTERN - an <b>exact</b> value, not a prefix or
 * substring: the type exists so an identifier is checkable, and a partial match would turn a
 * checkable identifier back into the free-text search the specification rules out.
 */
public record LibraryFieldCondition(
    UUID libraryId,
    String fieldKey,
    LibraryMetadataFieldType type,
    Set<String> codes,
    LocalDate dateFrom,
    LocalDate dateTo,
    String value) {

  public LibraryFieldCondition {
    codes = codes == null ? Set.of() : Set.copyOf(codes);
    if (libraryId == null || fieldKey == null || type == null) {
      throw new ValidationException("Ein Bibliotheksfeld-Filter braucht Bibliothek, Feld und Typ");
    }
    if (dateFrom != null && dateTo != null && dateTo.isBefore(dateFrom)) {
      throw new ValidationException("Das Datumsfenster endet vor seinem Beginn");
    }
  }

  public static LibraryFieldCondition ofCodes(
      UUID libraryId, String fieldKey, java.util.Collection<String> codes) {
    return new LibraryFieldCondition(
        libraryId,
        fieldKey,
        LibraryMetadataFieldType.SELECT,
        new LinkedHashSet<>(codes),
        null,
        null,
        null);
  }

  public static LibraryFieldCondition ofDateWindow(
      UUID libraryId, String fieldKey, LocalDate from, LocalDate to) {
    return new LibraryFieldCondition(
        libraryId, fieldKey, LibraryMetadataFieldType.DATE, Set.of(), from, to, null);
  }

  public static LibraryFieldCondition ofValue(UUID libraryId, String fieldKey, String value) {
    return new LibraryFieldCondition(
        libraryId, fieldKey, LibraryMetadataFieldType.PATTERN, Set.of(), null, null, value);
  }

  /**
   * The API shape - one of three mutually exclusive condition shapes - as a condition. The shape
   * itself names the type; stating two shapes at once, or none, is a caller error (400), and the
   * shape is checked against the field's actual type when the filter is validated.
   */
  public static LibraryFieldCondition parse(
      UUID libraryId,
      String fieldKey,
      java.util.Collection<String> codes,
      String dateFrom,
      String dateTo,
      String value) {
    boolean hasCodes = codes != null && !codes.isEmpty();
    boolean hasWindow =
        (dateFrom != null && !dateFrom.isBlank()) || (dateTo != null && !dateTo.isBlank());
    boolean hasValue = value != null && !value.isBlank();
    if ((hasCodes ? 1 : 0) + (hasWindow ? 1 : 0) + (hasValue ? 1 : 0) != 1) {
      throw new ValidationException(
          "Ein Bibliotheksfeld-Filter trägt genau eine Bedingung: Codes, ein Datumsfenster"
              + " oder einen Wert (Feld "
              + fieldKey
              + ")");
    }
    if (hasCodes) {
      return ofCodes(libraryId, fieldKey, codes);
    }
    if (hasValue) {
      return ofValue(libraryId, fieldKey, value.strip());
    }
    return ofDateWindow(libraryId, fieldKey, parseDate(dateFrom), parseDate(dateTo));
  }

  private static LocalDate parseDate(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(value.strip());
    } catch (java.time.format.DateTimeParseException e) {
      throw new ValidationException("Ungültiges Datum im Metadatenfilter: " + value);
    }
  }

  /** A condition that constrains nothing - the caller drops it rather than translating it. */
  public boolean isEmpty() {
    return switch (type) {
      case SELECT -> codes.isEmpty();
      case DATE -> dateFrom == null && dateTo == null;
      case PATTERN -> value == null || value.isBlank();
    };
  }

  /** The chunk metadata key this condition reads - see {@link LibraryMetadataFieldKeys}. */
  public String chunkKey() {
    return LibraryMetadataFieldKeys.chunkKey(fieldKey);
  }

  /** The chunk metadata key of a DATE field's precision. */
  public String precisionChunkKey() {
    return LibraryMetadataFieldKeys.precisionChunkKey(fieldKey);
  }

  /** The stored value key in {@code document_metadata_values}. */
  public String documentFieldKey() {
    return LibraryMetadataFieldKeys.documentFieldKey(fieldKey);
  }

  /** See {@link MetadataFilter#dateFromBound} - the same span arithmetic, on this field. */
  public LocalDate dateFromBound(DatePrecision precision) {
    if (dateFrom == null) {
      return null;
    }
    return switch (precision) {
      case DAY -> dateFrom;
      case MONTH -> dateFrom.withDayOfMonth(1);
      case YEAR -> dateFrom.withDayOfYear(1);
    };
  }

  /**
   * Whether a document of {@code documentLibraryId} carrying {@code storedValue} qualifies. A
   * document without a value for this field is never excluded (Leerwert-Regel), and a document of
   * another library has no value for this field by construction.
   */
  public boolean matches(
      UUID documentLibraryId, String storedValue, LocalDate storedDate, DatePrecision precision) {
    if (!libraryId.equals(documentLibraryId)) {
      return true;
    }
    return switch (type) {
      case SELECT -> storedValue == null || codes.contains(storedValue);
      case PATTERN -> storedValue == null || storedValue.equals(value);
      case DATE -> {
        if (storedDate == null) {
          yield true;
        }
        DatePrecision effective = precision == null ? DatePrecision.DAY : precision;
        if (dateTo != null && storedDate.isAfter(dateTo)) {
          yield false;
        }
        LocalDate lower = dateFromBound(effective);
        yield lower == null || !storedDate.isBefore(lower);
      }
    };
  }

  /** Whether a document is kept by the Leerwert rule alone for this condition. */
  public boolean keptWithoutValue(
      UUID documentLibraryId, String storedValue, LocalDate storedDate) {
    if (!libraryId.equals(documentLibraryId)) {
      return true;
    }
    return type == LibraryMetadataFieldType.DATE ? storedDate == null : storedValue == null;
  }
}
