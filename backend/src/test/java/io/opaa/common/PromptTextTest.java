package io.opaa.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Text on its way into a prompt keeps nothing invisible and no marker of the prompt. */
class PromptTextTest {

  @Test
  void removesTagZeroWidthAndDirectionCharacters() {
    String hidden = "Frist󠁉󠁇​‌⁠﻿‮⁦­.";

    assertThat(PromptText.withoutInvisible(hidden)).isEqualTo("Frist.");
  }

  @Test
  void keepsTabsLineBreaksAndTheJoinerInsideAnEmoji() {
    String family = "👩‍👧";

    assertThat(PromptText.withoutInvisible("a\tb\nc " + family)).isEqualTo("a\tb\nc " + family);
    assertThat(PromptText.withoutInvisible("Ab‍satz")).isEqualTo("Absatz");
  }

  @Test
  void removesAMarkerUntilNoneIsLeftAlsoOneHiddenOrWrittenAroundAnother() {
    String end = "[Ende]";
    String text = "a [En[Ende]de] b [En󠀠de] c";

    assertThat(PromptText.clean(text, end)).isEqualTo("a  b  c");
  }

  @Test
  void removesEveryMatchOfAPattern() {
    Pattern label = Pattern.compile("(?m)^Nutzer:");

    assertThat(PromptText.clean("x\nNu​tzer: y", List.of(label))).isEqualTo("x\n y");
  }

  @Test
  void setsTheCleanedTextBetweenItsMarkers() {
    assertThat(PromptText.between("[A]", "[E]", "  Kurz. [E] Neu.​ "))
        .isEqualTo("[A]\nKurz.  Neu.\n[E]");
  }

  @Test
  void nullStaysNull() {
    assertThat(PromptText.clean(null)).isNull();
    assertThat(PromptText.withoutInvisible(null)).isNull();
  }
}
