package io.opaa.indexing.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The two matching modes of the vocabulary (#1263): {@link DocumentTypeVocabulary#resolve} stays
 * the exact one every declared and manually entered value is validated against, while {@link
 * DocumentTypeVocabulary#resolveToken} adds the seeded Kompositum endings for a token read out of a
 * file name or a Dokumentkopf.
 */
class DocumentTypeVocabularyTest {

  private final DocumentTypeVocabulary vocabulary = TestVocabularies.delivered();

  @Test
  void aDeclaredValueIsNeverResolvedThroughAKompositumEnding() {
    assertThat(vocabulary.resolve("verwaltungsgebuehrensatzung")).isEmpty();
    assertThat(vocabulary.resolve("satzung")).contains("SATZUNG_ORDNUNG");
  }

  @Test
  void aTokenIsResolvedExactlyFirstAndThroughItsEndingSecond() {
    assertThat(vocabulary.resolveToken("Satzung")).contains("SATZUNG_ORDNUNG");
    assertThat(vocabulary.resolveToken("Verwaltungsgebührensatzung")).contains("SATZUNG_ORDNUNG");
    assertThat(vocabulary.resolveToken("rundschreiben")).isEmpty();
    assertThat(vocabulary.resolveToken(null)).isEmpty();
  }

  @Test
  void aTokenThatFitsTwoDokumentartenAtOnceResolvesToNeither() {
    DocumentTypeVocabulary ambiguous =
        DocumentTypeVocabulary.of(
            java.util.List.of(
                new DocumentTypeVocabularyEntry(
                    "PROTOKOLL",
                    "Protokoll",
                    10,
                    java.util.Set.of(),
                    java.util.Set.of(new DocumentTypeSuffix("bericht", 3)),
                    java.util.Set.of()),
                new DocumentTypeVocabularyEntry(
                    "VERMERK",
                    "Vermerk",
                    20,
                    java.util.Set.of(),
                    java.util.Set.of(new DocumentTypeSuffix("sbericht", 3)),
                    java.util.Set.of())));

    assertThat(ambiguous.resolveToken("Jahresbericht")).isEmpty();
  }
}
