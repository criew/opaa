package io.opaa.indexing.format.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The one heading notation a plain text file has, and only at the very start of a document. */
class SetextHeadingTest {

  @Test
  void anUnderlinedOpeningLineIsTheHeading() {
    assertThat(SetextHeading.leadingOf("Wunschkennzeichen\n=================\n\nText."))
        .isEqualTo("Wunschkennzeichen");
    assertThat(SetextHeading.leadingOf("\n\n  Wunschkennzeichen  \n===\nText."))
        .isEqualTo("Wunschkennzeichen");
  }

  @Test
  void anUnderlineFurtherDownIsNoHeading() {
    assertThat(SetextHeading.leadingOf("Zustaendige Stelle: Buergerbuero\n\nTabelle\n=======\n"))
        .isNull();
  }

  @Test
  void aShortRunOfEqualSignsIsNoUnderline() {
    assertThat(SetextHeading.leadingOf("Formel\n==\nText.")).isNull();
    assertThat(SetextHeading.leadingOf("a = b\n")).isNull();
  }

  @Test
  void anUnderlineWithoutALineAboveItIsNoHeading() {
    assertThat(SetextHeading.leadingOf("=====\n=====\n")).isNull();
    assertThat(SetextHeading.leadingOf("Ohne Unterstreichung\n")).isNull();
    assertThat(SetextHeading.leadingOf(null)).isNull();
    assertThat(SetextHeading.leadingOf("")).isNull();
  }
}
