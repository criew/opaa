package io.opaa.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.springframework.ai.reader.ExtractedTextFormatter;

/** Blank-line collapsing with Spring AI's result, without its stack depth per blank line. */
class AdjacentBlankLinesTest {

  private static final char[] ALPHABET = {
    'a', ' ', '\t', '\f', '\n', '\n', '\n', '\r', '\r', '\u0085', '\u2028', '\u2029'
  };

  @Test
  void everyShortTextComesOutAsSpringsFormatterLeavesIt() {
    SplittableRandom random = new SplittableRandom(3379);
    for (int i = 0; i < 200_000; i++) {
      char[] chars = new char[random.nextInt(0, 32)];
      for (int j = 0; j < chars.length; j++) {
        chars[j] = ALPHABET[random.nextInt(ALPHABET.length)];
      }
      String text = new String(chars);

      assertThat(AdjacentBlankLines.trim(text))
          .as("%s", text.chars().mapToObj(Integer::toHexString).toList())
          .isEqualTo(ExtractedTextFormatter.defaults().format(text));
    }
  }

  @Test
  void runsOfBlankLinesCollapseToOne() {
    assertThat(AdjacentBlankLines.trim("Absatz\n  \n\n\nNächster\r\n\r\n\r\nLetzter"))
        .isEqualTo("Absatz\n\nNächster\r\n\r\nLetzter");
  }

  @Test
  void aLongRunOfBlankLinesIsCollapsedInLinearTime() {
    String text = "Anfang" + "\n".repeat(2_000_000) + "Mitte" + "\r\n".repeat(1_000_000) + "Ende";

    String trimmed =
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> AdjacentBlankLines.trim(text));

    assertThat(trimmed).isEqualTo("Anfang\n\nMitte\r\n\r\nEnde");
  }
}
