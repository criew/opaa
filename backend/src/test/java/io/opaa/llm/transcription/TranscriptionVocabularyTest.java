package io.opaa.llm.transcription;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TranscriptionVocabularyTest {

  @Test
  void noListMeansNoPrompt() {
    assertThat(TranscriptionVocabulary.prompt(null, null)).isNull();
    assertThat(TranscriptionVocabulary.prompt(" ", "")).isNull();
  }

  @Test
  void eitherListAloneIsThePrompt() {
    assertThat(TranscriptionVocabulary.prompt("AO, BZSt", null)).isEqualTo("AO, BZSt");
    assertThat(TranscriptionVocabulary.prompt("", " Referat Z 4 ")).isEqualTo("Referat Z 4");
  }

  /** The person's list comes last: Whisper keeps the end of a prompt that is too long. */
  @Test
  void thePersonalListComesLast() {
    assertThat(TranscriptionVocabulary.prompt("AO, BZSt", "Referat Z 4"))
        .isEqualTo("AO, BZSt, Referat Z 4");
    assertThat(TranscriptionVocabulary.prompt("Es geht um die AO.", "Referat Z 4"))
        .isEqualTo("Es geht um die AO. Referat Z 4");
  }

  @Test
  void aListLongerThanTheCapIsRefused() {
    assertThat(TranscriptionVocabulary.isValid("a".repeat(500))).isTrue();
    assertThat(TranscriptionVocabulary.isValid("a".repeat(501))).isFalse();
    assertThat(TranscriptionVocabulary.isValid(null)).isTrue();
  }
}
