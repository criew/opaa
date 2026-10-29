package io.opaa.format.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.regex.Matcher;
import org.junit.jupiter.api.Test;

/** The one reading of an ATX heading, as a whole line and inside a longer text. */
class MarkdownHeadingTest {

  @Test
  void aHeadingLineWithALongRunOfInnerSpacesIsMatchedInLinearTime() {
    // regression guard: the trailing run must not be found by lazy backtracking over the title
    String line = "# a" + " ".repeat(100_000) + "x";

    Matcher matcher =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () -> {
              Matcher m = MarkdownHeading.LINE.matcher(line);
              assertThat(m.matches()).isTrue();
              return m;
            });

    assertThat(matcher.group(MarkdownHeading.LEVEL_GROUP)).isEqualTo("#");
  }

  @Test
  void aHeadingInsideATextWithALongRunOfInnerSpacesIsFoundInLinearTime() {
    String text = "Vorspann\n## a" + " ".repeat(100_000) + "x\nRest";

    Matcher matcher =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () -> {
              Matcher m = MarkdownHeading.IN_TEXT.matcher(text);
              assertThat(m.find()).isTrue();
              return m;
            });

    assertThat(matcher.group(MarkdownHeading.LEVEL_GROUP)).isEqualTo("##");
  }

  @Test
  void theTitleLosesItsClosingRunButNeverItsFirstCharacter() {
    assertThat(title("## Fristen ##  ")).isEqualTo("Fristen");
    assertThat(title("#\tFristen\t# #")).isEqualTo("Fristen");
    assertThat(title("# C#")).isEqualTo("C");
    assertThat(title("# #")).isEqualTo("#");
    assertThat(title("### 4.2 Fristsetzung")).isEqualTo("4.2 Fristsetzung");
    assertThat(MarkdownHeading.LINE.matcher("#   ").matches()).isFalse();
    assertThat(MarkdownHeading.LINE.matcher("####### sieben").matches()).isFalse();
  }

  private static String title(String line) {
    Matcher matcher = MarkdownHeading.LINE.matcher(line);
    assertThat(matcher.matches()).isTrue();
    return MarkdownHeading.title(matcher);
  }
}
