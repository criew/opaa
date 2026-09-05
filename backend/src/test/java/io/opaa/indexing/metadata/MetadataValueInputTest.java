package io.opaa.indexing.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.DatePrecision;
import io.opaa.common.ValidationException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The field-aware validation of a manual value: nothing is guessed or mapped. */
class MetadataValueInputTest {

  private static final DocumentTypeVocabulary VOCABULARY =
      DocumentTypeVocabulary.of(
          List.of(
              new DocumentTypeVocabularyEntry("VERMERK", "Vermerk", 30, Set.of("aktenvermerk"))));

  @Test
  void aTitleIsTrimmedAndMustNotBeBlank() {
    assertThat(
            MetadataValueInput.text("  Neuer Titel ")
                .validatedFor(CoreMetadataField.TITLE, VOCABULARY))
        .isEqualTo(MetadataValueInput.text("Neuer Titel"));
    assertThatThrownBy(
            () -> MetadataValueInput.text(" ").validatedFor(CoreMetadataField.TITLE, VOCABULARY))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Der Titel darf nicht leer sein");
    assertThatThrownBy(
            () ->
                MetadataValueInput.text("x".repeat(1001))
                    .validatedFor(CoreMetadataField.TITLE, VOCABULARY))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void aDokumentartMustBeAVocabularyCodeNotASynonymOrLabel() {
    assertThat(
            MetadataValueInput.vocabulary("VERMERK")
                .validatedFor(CoreMetadataField.DOCUMENT_TYPE, VOCABULARY))
        .isEqualTo(MetadataValueInput.vocabulary("VERMERK"));
    for (String rejected : List.of("aktenvermerk", "Vermerk", "RUNDSCHREIBEN")) {
      assertThatThrownBy(
              () ->
                  MetadataValueInput.vocabulary(rejected)
                      .validatedFor(CoreMetadataField.DOCUMENT_TYPE, VOCABULARY))
          .as(rejected)
          .isInstanceOf(ValidationException.class)
          .hasMessage("Unbekannte Dokumentart: " + rejected);
    }
  }

  @Test
  void aDateIsPaddedToItsPrecisionAndNeedsOne() {
    assertThat(
            MetadataValueInput.date(LocalDate.of(2024, 5, 17), DatePrecision.YEAR)
                .validatedFor(CoreMetadataField.DOCUMENT_DATE, VOCABULARY))
        .isEqualTo(MetadataValueInput.date(LocalDate.of(2024, 1, 1), DatePrecision.YEAR));
    assertThat(
            MetadataValueInput.date(LocalDate.of(2024, 5, 17), DatePrecision.MONTH)
                .validatedFor(CoreMetadataField.DOCUMENT_DATE, VOCABULARY))
        .isEqualTo(MetadataValueInput.date(LocalDate.of(2024, 5, 1), DatePrecision.MONTH));
    assertThatThrownBy(
            () ->
                MetadataValueInput.date(LocalDate.of(2024, 5, 17), null)
                    .validatedFor(CoreMetadataField.DOCUMENT_DATE, VOCABULARY))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Für das Datum ist eine Genauigkeit erforderlich");
  }

  @Test
  void aValueOfTheWrongKindForTheFieldIsRejected() {
    assertThatThrownBy(
            () ->
                MetadataValueInput.text("2024")
                    .validatedFor(CoreMetadataField.DOCUMENT_DATE, VOCABULARY))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                new MetadataValueInput(MetadataValueState.SET, "Titel", "VERMERK", null, null, null)
                    .validatedFor(CoreMetadataField.TITLE, VOCABULARY))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                new MetadataValueInput(MetadataValueState.SET, null, null, null, null, null)
                    .validatedFor(CoreMetadataField.DOCUMENT_TYPE, VOCABULARY))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void theThirdStateIsValidForEveryFieldAndOnlyWithoutAValue() {
    for (CoreMetadataField field : CoreMetadataField.values()) {
      assertThat(MetadataValueInput.notDeterminable().validatedFor(field, VOCABULARY))
          .isEqualTo(MetadataValueInput.notDeterminable());
    }
    assertThatThrownBy(
            () ->
                new MetadataValueInput(
                        MetadataValueState.NOT_DETERMINABLE, "Titel", null, null, null, null)
                    .validatedFor(CoreMetadataField.TITLE, VOCABULARY))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("ohne Wert");
  }
}
