package io.opaa.indexing.metadata;

import io.opaa.api.types.DatePrecision;
import io.opaa.api.types.LibraryMetadataFieldType;
import io.opaa.common.ValidationException;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * The value a person sets for one core field: either a value - exactly one of {@code textValue},
 * {@code vocabularyCode} or {@code dateValue}+{@code datePrecision} - or the third state "kein Wert
 * ermittelbar" ({@link MetadataValueState#NOT_DETERMINABLE}), which carries no value at all. {@link
 * #validatedFor} checks the input against the field's type and the vocabulary and normalises the
 * date to the first day of what its precision leaves unknown.
 */
public record MetadataValueInput(
    MetadataValueState state,
    String textValue,
    String vocabularyCode,
    LocalDate dateValue,
    DatePrecision datePrecision,
    UUID libraryValueId) {

  private static final int MAX_TEXT_LENGTH = 1000;

  /** A library field's value never exceeds its column; the pattern check bounds it in practice. */
  private static final int MAX_LIBRARY_TEXT_LENGTH = 200;

  public MetadataValueInput {
    state = state == null ? MetadataValueState.SET : state;
  }

  public static MetadataValueInput text(String textValue) {
    return new MetadataValueInput(MetadataValueState.SET, textValue, null, null, null, null);
  }

  public static MetadataValueInput vocabulary(String code) {
    return new MetadataValueInput(MetadataValueState.SET, null, code, null, null, null);
  }

  public static MetadataValueInput date(LocalDate date, DatePrecision precision) {
    return new MetadataValueInput(MetadataValueState.SET, null, null, date, precision, null);
  }

  /** A chosen entry of a library SELECT field's value list: its code and the entry's own id. */
  public static MetadataValueInput libraryValue(String code, UUID libraryValueId) {
    return new MetadataValueInput(MetadataValueState.SET, code, null, null, null, libraryValueId);
  }

  /**
   * "Eine Person hat festgestellt, dass es keinen gibt" - set by hand for any core field, without a
   * value (metadata-schema.md, "Kein Wert ermittelbar ist ein dritter Zustand").
   */
  public static MetadataValueInput notDeterminable() {
    return new MetadataValueInput(
        MetadataValueState.NOT_DETERMINABLE, null, null, null, null, null);
  }

  /**
   * The same input, checked and normalised for {@code field}: a blank title, a Dokumentart outside
   * {@code vocabulary}, a date without precision or a value of the wrong kind for the field is a
   * {@link ValidationException} ("lieber leer als geraten" - nothing is mapped to the nearest
   * value). "Kein Wert ermittelbar" is valid for every field and must come without a value.
   */
  public MetadataValueInput validatedFor(
      CoreMetadataField field, DocumentTypeVocabulary vocabulary) {
    if (state == MetadataValueState.NOT_DETERMINABLE) {
      if (textValue != null || vocabularyCode != null || dateValue != null) {
        throw new ValidationException(
            "„Kein Wert ermittelbar“ wird ohne Wert gesetzt (Feld " + field.label() + ")");
      }
      return notDeterminable();
    }
    return switch (field) {
      case TITLE -> {
        requireOnly(field, textValue != null, vocabularyCode == null && dateValue == null);
        String trimmed = textValue.strip();
        if (trimmed.isEmpty()) {
          throw new ValidationException("Der Titel darf nicht leer sein");
        }
        if (trimmed.length() > MAX_TEXT_LENGTH) {
          throw new ValidationException(
              "Der Titel darf höchstens " + MAX_TEXT_LENGTH + " Zeichen lang sein");
        }
        yield text(trimmed);
      }
      case DOCUMENT_TYPE -> {
        requireOnly(field, vocabularyCode != null, textValue == null && dateValue == null);
        String code = vocabularyCode.strip();
        if (!vocabulary.containsCode(code)) {
          throw new ValidationException("Unbekannte Dokumentart: " + vocabularyCode);
        }
        yield vocabulary(code);
      }
      case DOCUMENT_DATE -> {
        requireOnly(field, dateValue != null, textValue == null && vocabularyCode == null);
        if (datePrecision == null) {
          throw new ValidationException("Für das Datum ist eine Genauigkeit erforderlich");
        }
        yield date(padToPrecision(dateValue, datePrecision), datePrecision);
      }
    };
  }

  /**
   * The same input, checked and normalised for the library field {@code field}: a SELECT value must
   * name an entry of {@code valuesByCode} - nothing is mapped to a near match, and the resolved
   * entry id is what the database checks; a DATE value follows the core date rules; a PATTERN value
   * must match the pattern the field definition carries, which is the whole reason the pattern
   * belongs to the definition. "Kein Wert ermittelbar" is valid for a library field exactly as for
   * a core field.
   */
  public MetadataValueInput validatedForLibraryField(
      LibraryMetadataField field, Map<String, LibraryMetadataFieldValue> valuesByCode) {
    if (state == MetadataValueState.NOT_DETERMINABLE) {
      if (textValue != null || vocabularyCode != null || dateValue != null) {
        throw new ValidationException(
            "„Kein Wert ermittelbar“ wird ohne Wert gesetzt (Feld " + field.getLabel() + ")");
      }
      return notDeterminable();
    }
    if (vocabularyCode != null) {
      requireLibraryOnly(field, false, false);
    }
    return switch (field.getType()) {
      case SELECT -> {
        requireLibraryOnly(field, textValue != null, dateValue == null);
        String code = textValue.strip();
        LibraryMetadataFieldValue entry = valuesByCode.get(code);
        if (entry == null) {
          throw new ValidationException(
              "Der Wert „"
                  + code
                  + "“ steht nicht in der Werteliste des Feldes "
                  + field.getLabel());
        }
        yield libraryValue(entry.getCode(), entry.getId());
      }
      case DATE -> {
        requireLibraryOnly(field, dateValue != null, textValue == null);
        if (datePrecision == null) {
          throw new ValidationException(
              "Für das Feld " + field.getLabel() + " ist eine Genauigkeit erforderlich");
        }
        yield date(padToPrecision(dateValue, datePrecision), datePrecision);
      }
      case PATTERN -> {
        requireLibraryOnly(field, textValue != null, dateValue == null);
        String value = textValue.strip();
        if (value.isEmpty()) {
          throw new ValidationException(
              "Der Wert für " + field.getLabel() + " darf nicht leer sein");
        }
        if (value.length() > MAX_LIBRARY_TEXT_LENGTH) {
          throw new ValidationException(
              "Der Wert für "
                  + field.getLabel()
                  + " darf höchstens "
                  + MAX_LIBRARY_TEXT_LENGTH
                  + " Zeichen lang sein");
        }
        if (!BoundedRegex.matchesWithinBudget(
            BoundedRegex.compile(field), value, field.getLabel())) {
          throw new ValidationException(
              "Der Wert „"
                  + value
                  + "“ entspricht nicht dem Muster des Feldes "
                  + field.getLabel()
                  + " ("
                  + field.getValuePattern()
                  + ")");
        }
        yield text(value);
      }
    };
  }

  private static void requireLibraryOnly(
      LibraryMetadataField field, boolean ownValuePresent, boolean otherValuesAbsent) {
    if (!ownValuePresent || !otherValuesAbsent) {
      throw new ValidationException(
          "Der Wert passt nicht zum Feld "
              + field.getLabel()
              + " ("
              + field.getFieldKey()
              + ", "
              + typeLabel(field.getType())
              + ")");
    }
  }

  private static String typeLabel(LibraryMetadataFieldType type) {
    return switch (type) {
      case SELECT -> "Auswahl";
      case DATE -> "Jahr/Datum";
      case PATTERN -> "Kennung nach Muster";
    };
  }

  /** Applies this (validated) value to {@code target}. */
  void applyTo(DocumentMetadataValue target) {
    if (state == MetadataValueState.NOT_DETERMINABLE) {
      target.assignNotDeterminable();
    } else if (libraryValueId != null) {
      target.assignLibraryValue(textValue, libraryValueId);
    } else if (textValue != null) {
      target.assignText(textValue);
    } else if (vocabularyCode != null) {
      target.assignVocabularyCode(vocabularyCode);
    } else {
      target.assignDate(dateValue, datePrecision);
    }
  }

  private static void requireOnly(
      CoreMetadataField field, boolean ownValuePresent, boolean otherValuesAbsent) {
    if (!ownValuePresent || !otherValuesAbsent) {
      throw new ValidationException(
          "Der Wert passt nicht zum Feld " + field.label() + " (" + field.key() + ")");
    }
  }

  private static LocalDate padToPrecision(LocalDate date, DatePrecision precision) {
    return switch (precision) {
      case DAY -> date;
      case MONTH -> date.withDayOfMonth(1);
      case YEAR -> date.withDayOfYear(1);
    };
  }
}
