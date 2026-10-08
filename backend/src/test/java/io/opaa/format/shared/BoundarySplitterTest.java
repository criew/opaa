package io.opaa.format.shared;

import static org.assertj.core.api.Assertions.assertThat;

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
}
