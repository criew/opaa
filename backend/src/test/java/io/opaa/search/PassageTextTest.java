package io.opaa.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class PassageTextTest {

  @Test
  void joinWritesTheSharedOverlapOfTwoPassagesOnlyOnce() {
    String first = "Die Frist beträgt einen Monat. Sie beginnt mit der Bekanntgabe.";
    String second = "Sie beginnt mit der Bekanntgabe. Der Widerspruch ist schriftlich zu erheben.";

    String joined = PassageText.join(List.of(first, second));

    assertThat(joined)
        .isEqualTo(
            "Die Frist beträgt einen Monat. Sie beginnt mit der Bekanntgabe."
                + " Der Widerspruch ist schriftlich zu erheben.");
    assertThat(joined.split("Sie beginnt mit der Bekanntgabe", -1)).hasSize(2);
  }

  @Test
  void joinSeparatesPassagesThatShareNothing() {
    assertThat(PassageText.join(List.of("Erster Absatz.", "Zweiter Absatz.")))
        .isEqualTo("Erster Absatz.\n\nZweiter Absatz.");
  }

  @Test
  void joinSkipsEmptyAndNullPassages() {
    assertThat(PassageText.join(Arrays.asList(null, "", "Text.", null))).isEqualTo("Text.");
    assertThat(PassageText.join(List.of())).isEmpty();
  }

  @Test
  void headingPathReadsTheSectionsOfAFundort() {
    assertThat(PassageText.headingPath("Abschn. Verfahren › Fristsetzung"))
        .containsExactly("Verfahren", "Fristsetzung");
  }

  @Test
  void headingPathIsEmptyForAFundortThatNamesAPositionRatherThanAStructure() {
    assertThat(PassageText.headingPath("S. 2-4")).isEmpty();
    assertThat(PassageText.headingPath(null)).isEmpty();
    assertThat(PassageText.headingPath("Abschn. ")).isEmpty();
  }

  @Test
  void truncateCutsAtTheLimitAndSaysSo() {
    assertThat(PassageText.truncate("abcdef", 10))
        .isEqualTo(new PassageText.Truncation("abcdef", false));
    assertThat(PassageText.truncate("abcdef", 3))
        .isEqualTo(new PassageText.Truncation("abc", true));
  }
}
