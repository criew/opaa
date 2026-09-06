package io.opaa.indexing.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The answer parser of the model step (#1073): what it reads, and what it refuses to guess. */
class ModelExtractionAnswerTest {

  @Test
  void readsValuesConfidencesAndKeywords() {
    ModelExtractionAnswer answer =
        ModelExtractionAnswer.parse(
            """
            {"fields": {"document_type": {"value": "SATZUNG_ORDNUNG", "confidence": 0.91}},
             "keywords": ["Fahrradstellplatz", "Rathaus"]}
            """);

    assertThat(answer.valueOf("document_type"))
        .hasValue(new ModelExtractionAnswer.ProposedValue("SATZUNG_ORDNUNG", 0.91));
    assertThat(answer.keywords()).containsExactly("Fahrradstellplatz", "Rathaus");
  }

  @Test
  void toleratesAFencedCodeBlockAndSurroundingProse() {
    ModelExtractionAnswer answer =
        ModelExtractionAnswer.parse(
            """
            Gerne! Hier das Ergebnis:
            ```json
            {"fields": {"document_type": {"value": "VERMERK", "confidence": 0.5}}}
            ```
            """);

    assertThat(answer.valueOf("document_type"))
        .hasValue(new ModelExtractionAnswer.ProposedValue("VERMERK", 0.5));
  }

  @Test
  void anEmptyValueIsNoValueAndAMissingConfidenceStaysAbsent() {
    ModelExtractionAnswer answer =
        ModelExtractionAnswer.parse(
            """
            {"fields": {"document_type": {"value": null}, "lib:fassung": {"value": "  "}}}
            """);

    assertThat(answer.valueOf("document_type"))
        .hasValue(new ModelExtractionAnswer.ProposedValue(null, null));
    assertThat(answer.valueOf("lib:fassung"))
        .hasValue(new ModelExtractionAnswer.ProposedValue(null, null));
  }

  @Test
  void unusableOutputYieldsNothingRatherThanAnException() {
    assertThat(ModelExtractionAnswer.parse("Ich kann das leider nicht beantworten."))
        .isEqualTo(ModelExtractionAnswer.EMPTY);
    assertThat(ModelExtractionAnswer.parse("{ kaputt")).isEqualTo(ModelExtractionAnswer.EMPTY);
    assertThat(ModelExtractionAnswer.parse(null)).isEqualTo(ModelExtractionAnswer.EMPTY);
    assertThat(ModelExtractionAnswer.parse("")).isEqualTo(ModelExtractionAnswer.EMPTY);
  }

  @Test
  void aKeywordThatIsNotAStringIsDropped() {
    ModelExtractionAnswer answer =
        ModelExtractionAnswer.parse(
            """
            {"keywords": ["gut", 7, null, "  "]}
            """);

    assertThat(answer.keywords()).containsExactly("gut");
  }
}
