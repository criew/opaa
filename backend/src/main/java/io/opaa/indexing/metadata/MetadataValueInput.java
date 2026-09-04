package io.opaa.indexing.metadata;

import io.opaa.api.types.DatePrecision;
import io.opaa.common.ValidationException;
import java.time.LocalDate;

/**
 * The value a person sets for one core field (#1068): exactly one of {@code textValue}, {@code
 * vocabularyCode} or {@code dateValue}+{@code datePrecision}. {@link #validatedFor} checks the
 * value against the field's type and the vocabulary and normalises the date to the first day of
 * what its precision leaves unknown. #1069 adds the third state "kein Wert ermittelbar" as one more
 * alternative here, not as a separate operation.
 */
public record MetadataValueInput(
    String textValue, String vocabularyCode, LocalDate dateValue, DatePrecision datePrecision) {

  private static final int MAX_TEXT_LENGTH = 1000;

  public static MetadataValueInput text(String textValue) {
    return new MetadataValueInput(textValue, null, null, null);
  }

  public static MetadataValueInput vocabulary(String code) {
    return new MetadataValueInput(null, code, null, null);
  }

  public static MetadataValueInput date(LocalDate date, DatePrecision precision) {
    return new MetadataValueInput(null, null, date, precision);
  }

  /**
   * The same input, checked and normalised for {@code field}: a blank title, a Dokumentart outside
   * {@code vocabulary}, a date without precision or a value of the wrong kind for the field is a
   * {@link ValidationException} ("lieber leer als geraten" - nothing is mapped to the nearest
   * value).
   */
  public MetadataValueInput validatedFor(
      CoreMetadataField field, DocumentTypeVocabulary vocabulary) {
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

  /** Applies this (validated) value to {@code target}. */
  void applyTo(DocumentMetadataValue target) {
    if (textValue != null) {
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
