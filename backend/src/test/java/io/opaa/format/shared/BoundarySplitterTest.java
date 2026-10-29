package io.opaa.format.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoundarySplitterTest {

  @Test
  void aTextWithinTheLimitStaysOnePart() {
    assertThat(BoundarySplitter.split("  Ein kurzer Satz.  ", 100))
        .containsExactly("Ein kurzer Satz.");
  }

  @Test
  void aBlankLineWinsOverALaterLineBreak() {
    String text = "a".repeat(60) + "\n\n" + "b".repeat(30) + "\n" + "c".repeat(30);

    assertThat(BoundarySplitter.split(text, 100))
        .containsExactly("a".repeat(60), "b".repeat(30) + "\n" + "c".repeat(30));
  }

  @Test
  void aBoundaryInTheFirstHalfIsIgnoredSoNoPartBecomesTiny() {
    String text = "Kurz. " + "x".repeat(150);

    List<String> parts = BoundarySplitter.split(text, 100);

    assertThat(parts.getFirst()).hasSize(100);
    assertThat(String.join("", parts)).isEqualTo(text);
  }

  @Test
  void aHardCutNeverSeparatesASurrogatePair() {
    String text = "a".repeat(99) + "😀".repeat(10);

    List<String> parts = BoundarySplitter.split(text, 100);

    assertThat(parts.getFirst()).isEqualTo("a".repeat(99));
    assertThat(String.join("", parts)).isEqualTo(text);
  }

  @Test
  void aVeryLongTextIsSplitInLinearTime() {
    // regression guard: cutting must not copy the remaining text once per part
    String text = "Wort ".repeat(4_000_000);

    List<String> parts =
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> BoundarySplitter.split(text, 1000));

    assertThat(parts).hasSizeGreaterThan(19_000).allMatch(part -> part.length() <= 1000);
    assertThat(parts.getLast()).endsWith("Wort");
  }
}
