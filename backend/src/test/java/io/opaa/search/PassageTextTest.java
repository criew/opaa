package io.opaa.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class PassageTextTest {

  /** A cap far above every fixture of this class - the join itself is under test, not the cap. */
  private static final int NO_CAP = 100_000;

  @Test
  void joinWritesTheSharedOverlapOfTwoPassagesOnlyOnce() {
    String first = "Die Frist beträgt einen Monat. Sie beginnt mit der Bekanntgabe.";
    String second = "Sie beginnt mit der Bekanntgabe. Der Widerspruch ist schriftlich zu erheben.";

    String joined = PassageText.join(List.of(first, second), NO_CAP).text();

    assertThat(joined)
        .isEqualTo(
            "Die Frist beträgt einen Monat. Sie beginnt mit der Bekanntgabe."
                + " Der Widerspruch ist schriftlich zu erheben.");
    assertThat(joined.split("Sie beginnt mit der Bekanntgabe", -1)).hasSize(2);
  }

  @Test
  void joinSeparatesPassagesThatShareNothing() {
    assertThat(PassageText.join(List.of("Erster Absatz.", "Zweiter Absatz."), NO_CAP).text())
        .isEqualTo("Erster Absatz.\n\nZweiter Absatz.");
  }

  @Test
  void joinSkipsEmptyAndNullPassages() {
    assertThat(PassageText.join(Arrays.asList(null, "", "Text.", null), NO_CAP).text())
        .isEqualTo("Text.");
    assertThat(PassageText.join(List.of(), NO_CAP).text()).isEmpty();
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
  void theCapCutsAndSaysSo() {
    assertThat(PassageText.join(List.of("abcdef"), 10))
        .isEqualTo(new PassageText.Joined("abcdef", false));
    assertThat(PassageText.join(List.of("abcdef"), 3))
        .isEqualTo(new PassageText.Joined("abc", true));
  }

  /**
   * A cut that would split a surrogate pair drops the pair instead: a lone high surrogate is an
   * invalid character in the JSON response.
   */
  @Test
  void theCapNeverSplitsASurrogatePair() {
    String withEmoji = "ab" + new String(Character.toChars(0x1F642)) + "cd";

    PassageText.Joined cut = PassageText.join(List.of(withEmoji), 3);

    assertThat(cut.text()).isEqualTo("ab");
    assertThat(cut.truncated()).isTrue();
    assertThat(cut.text().chars().anyMatch(c -> Character.isHighSurrogate((char) c))).isFalse();
  }

  /** Once the cap is reached, a further passage cannot change the text - only the flag. */
  @Test
  void aFullJoinerIgnoresFurtherPassages() {
    PassageText.Joiner joiner = new PassageText.Joiner(5);
    joiner.append("abcdef");

    assertThat(joiner.isFull()).isTrue();
    joiner.append("ghijkl");

    assertThat(joiner.finish().text()).isEqualTo("abcde");
  }

  /**
   * The exact cap width: the text fits to the character, so nothing is cut - but a further passage
   * no longer fits and is dropped. That is a truncation, and the flag has to say so, or a client
   * treats an incomplete text as complete.
   */
  @Test
  void aPassageDroppedAtTheExactCapWidthCountsAsTruncation() {
    PassageText.Joined joined = PassageText.join(List.of("abcde", "fghij"), 5);

    assertThat(joined.text()).isEqualTo("abcde");
    assertThat(joined.truncated()).isTrue();
  }

  /** The same width without anything left over is not a truncation. */
  @Test
  void textThatFillsTheCapExactlyAndEndsThereIsNotTruncated() {
    PassageText.Joined joined = PassageText.join(List.of("abcde"), 5);

    assertThat(joined.text()).isEqualTo("abcde");
    assertThat(joined.truncated()).isFalse();
  }
}
