package io.opaa.indexing.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.DatePrecision;
import io.opaa.common.ValidationException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The domain rule of the core-field filter (#1070): the precision semantics of the date window, the
 * Leerwert rule, and what a filter refuses to carry.
 */
class MetadataFilterTest {

  private static final DocumentTypeVocabulary VOCABULARY =
      DocumentTypeVocabulary.of(
          List.of(
              new DocumentTypeVocabularyEntry("DIENSTANWEISUNG", "Dienstanweisung", 1, Set.of()),
              new DocumentTypeVocabularyEntry("VERMERK", "Vermerk", 2, Set.of())));

  /** "Fassung 2024" (YEAR, stored 2024-01-01) lies in 2024, not in 2023 - and overlaps mid-2024. */
  @Test
  void aYearValueCoversItsWholeYear() {
    MetadataFilter year2024 =
        MetadataFilter.ofDateWindow(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));
    MetadataFilter year2023 =
        MetadataFilter.ofDateWindow(LocalDate.of(2023, 1, 1), LocalDate.of(2023, 12, 31));
    MetadataFilter fromJune2024 = MetadataFilter.ofDateWindow(LocalDate.of(2024, 6, 1), null);
    LocalDate stored = LocalDate.of(2024, 1, 1);

    assertThat(year2024.matches(null, stored, DatePrecision.YEAR)).isTrue();
    assertThat(year2023.matches(null, stored, DatePrecision.YEAR)).isFalse();
    assertThat(fromJune2024.matches(null, stored, DatePrecision.YEAR)).isTrue();
    // The same stored day read as a DAY value is the 1st of January and lies outside June onwards.
    assertThat(fromJune2024.matches(null, stored, DatePrecision.DAY)).isFalse();
  }

  @Test
  void aMonthValueCoversItsWholeMonth() {
    MetadataFilter window =
        MetadataFilter.ofDateWindow(LocalDate.of(2024, 3, 15), LocalDate.of(2024, 4, 1));
    LocalDate march = LocalDate.of(2024, 3, 1);

    assertThat(window.matches(null, march, DatePrecision.MONTH)).isTrue();
    assertThat(window.matches(null, march, DatePrecision.DAY)).isFalse();
    assertThat(window.matches(null, LocalDate.of(2024, 2, 1), DatePrecision.MONTH)).isFalse();
    assertThat(window.dateFromBound(DatePrecision.MONTH)).isEqualTo(LocalDate.of(2024, 3, 1));
    assertThat(window.dateFromBound(DatePrecision.YEAR)).isEqualTo(LocalDate.of(2024, 1, 1));
    assertThat(window.dateFromBound(DatePrecision.DAY)).isEqualTo(LocalDate.of(2024, 3, 15));
  }

  /** Leerwerte schließen nicht aus: a missing value never disqualifies, and is reported as such. */
  @Test
  void aDocumentWithoutAValueForTheFilteredFieldIsKeptAndMarked() {
    MetadataFilter filter =
        new MetadataFilter(Set.of("VERMERK"), LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));

    assertThat(filter.matches(null, null, null)).isTrue();
    assertThat(filter.keptWithoutValue(null, null)).isTrue();
    assertThat(filter.matches("VERMERK", null, null)).isTrue();
    assertThat(filter.keptWithoutValue("VERMERK", null)).isTrue();
    assertThat(filter.matches("VERMERK", LocalDate.of(2024, 5, 5), DatePrecision.DAY)).isTrue();
    assertThat(filter.keptWithoutValue("VERMERK", LocalDate.of(2024, 5, 5))).isFalse();
    assertThat(filter.matches("DIENSTANWEISUNG", LocalDate.of(2024, 5, 5), DatePrecision.DAY))
        .isFalse();
  }

  @Test
  void aWindowEndingBeforeItsStartIsRejected() {
    assertThatThrownBy(
            () -> MetadataFilter.ofDateWindow(LocalDate.of(2024, 5, 1), LocalDate.of(2024, 4, 1)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void anImpossibleDateIsRejectedRatherThanMappedToANearbyDay() {
    assertThatThrownBy(() -> MetadataFilter.parse(List.of(), "2024-02-30", null))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("2024-02-30");
  }

  @Test
  void aCodeOutsideTheVocabularyIsRejected() {
    assertThatThrownBy(
            () ->
                MetadataFilter.ofDocumentTypes(List.of("SCHLAGWORT")).validatedAgainst(VOCABULARY))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("SCHLAGWORT");
    assertThat(MetadataFilter.ofDocumentTypes(List.of("VERMERK")).validatedAgainst(VOCABULARY))
        .isNotNull();
  }

  /**
   * "Nur Kernfelder filtern; freie Schlagworte nie": the filter's whole vocabulary is the two
   * filterable core fields - there is no component a keyword or the title could travel in.
   */
  @Test
  void onlyDocumentTypeAndDocumentDateAreFilterable() {
    assertThat(MetadataFilter.class.getRecordComponents())
        .extracting(component -> component.getName())
        .containsExactly("documentTypes", "documentDateFrom", "documentDateTo");
    assertThat(MetadataFilter.NONE.isEmpty()).isTrue();
    assertThat(MetadataFilter.parse(null, null, null).isEmpty()).isTrue();
  }
}
