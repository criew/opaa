package io.opaa.indexing.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The prompt of the model step (#1073): closed value list, no invention, capped document text. */
class ModelExtractionPromptTest {

  private static final ModelExtractionField DOCUMENT_TYPE =
      new ModelExtractionField(
          MetadataFieldRef.of(CoreMetadataField.DOCUMENT_TYPE),
          List.of(
              new ModelExtractionField.Option("SATZUNG_ORDNUNG", "Satzung/Ordnung", null),
              new ModelExtractionField.Option("VERMERK", "Vermerk", null)));

  @Test
  void offersEveryCodeWithItsLabelAndForbidsAnythingElse() {
    String prompt =
        ModelExtractionPrompt.build("Stellplatzsatzung", "Text", List.of(DOCUMENT_TYPE), false);

    assertThat(prompt).contains("document_type (Dokumentart)");
    assertThat(prompt).contains("SATZUNG_ORDNUNG = Satzung/Ordnung");
    assertThat(prompt).contains("VERMERK = Vermerk");
    assertThat(prompt).contains("Erfinde keinen Code");
    assertThat(prompt).contains("Stellplatzsatzung");
    assertThat(prompt).doesNotContain("Schlagworte");
  }

  @Test
  void asksForKeywordsOnlyWhenTheyAreSwitchedOn() {
    String prompt = ModelExtractionPrompt.build("Titel", "Text", List.of(), true);

    assertThat(prompt).contains("höchstens 5 freie Schlagworte");
    assertThat(prompt).contains("höchstens 40 Zeichen");
    assertThat(prompt).contains("Keine Personennamen");
  }

  @Test
  void capsTheDocumentTextSoTheAmountLeavingTheHouseIsBounded() {
    String text = "x".repeat(ModelExtractionPrompt.TEXT_LIMIT + 500);

    assertThat(ModelExtractionPrompt.capText(text)).hasSize(ModelExtractionPrompt.TEXT_LIMIT);
    assertThat(ModelExtractionPrompt.build("Titel", text, List.of(DOCUMENT_TYPE), false))
        .doesNotContain("x".repeat(ModelExtractionPrompt.TEXT_LIMIT + 1));
    assertThat(ModelExtractionPrompt.capText(null)).isEmpty();
  }
}
