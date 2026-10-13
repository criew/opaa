package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Only leading {@code <think>}/{@code <thought>} blocks, or the text before a lone closing {@code
 * </think>} line, are reasoning; the rest is the answer.
 */
class LeadingReasoningTextTest {

  @Test
  void aLeadingThinkBlockIsRemoved() {
    LeadingReasoningText.Stripped stripped =
        LeadingReasoningText.strip("<think>Der Nutzer fragt nach X.</think>\n\nAntwort [1]");

    assertThat(stripped.text()).isEqualTo("Antwort [1]");
    assertThat(stripped.removedCharacters())
        .isEqualTo("<think>Der Nutzer fragt nach X.</think>\n\n".length());
    assertThat(stripped.unclosed()).isFalse();
  }

  @Test
  void aLeadingThoughtBlockIsRemoved() {
    LeadingReasoningText.Stripped stripped =
        LeadingReasoningText.strip("<thought>*   Task: decompose.</thought>Kosten Personalausweis");

    assertThat(stripped.text()).isEqualTo("Kosten Personalausweis");
  }

  @Test
  void severalLeadingBlocksAndTheWhitespaceAroundThemAreRemoved() {
    LeadingReasoningText.Stripped stripped =
        LeadingReasoningText.strip(
            "  \n<think>eins</think>\n <thought>zwei</thought>\n\nAntwort\n  mit Einzug");

    assertThat(stripped.text()).isEqualTo("Antwort\n  mit Einzug");
  }

  @Test
  void aTagInsideTheAnswerIsKept() {
    String answer = "Das Tag <think>…</think> markiert Denktext.\n<thought>bleibt</thought>";

    LeadingReasoningText.Stripped stripped = LeadingReasoningText.strip(answer);

    assertThat(stripped.text()).isSameAs(answer);
    assertThat(stripped.removedCharacters()).isZero();
  }

  @Test
  void anAnswerWithoutABlockKeepsItsLeadingWhitespace() {
    assertThat(LeadingReasoningText.strip("  Antwort").text()).isEqualTo("  Antwort");
  }

  @Test
  void aClosingTagOfTheOtherKindDoesNotCloseTheBlock() {
    LeadingReasoningText.Stripped stripped =
        LeadingReasoningText.strip("<think>abgebrochen</thought>Antwort");

    assertThat(stripped.text()).isEmpty();
    assertThat(stripped.unclosed()).isTrue();
  }

  @Test
  void anUnclosedLeadingBlockLeavesNoAnswer() {
    String text = "<thought>Das Ausgabelimit war erreicht, bevor";

    LeadingReasoningText.Stripped stripped = LeadingReasoningText.strip(text);

    assertThat(stripped.text()).isEmpty();
    assertThat(stripped.removedCharacters()).isEqualTo(text.length());
    assertThat(stripped.unclosed()).isTrue();
  }

  @Test
  void anUnclosedBlockAfterAClosedOneLeavesNoAnswer() {
    assertThat(LeadingReasoningText.strip("<think>a</think><think>b").text()).isEmpty();
  }

  @Test
  void nullStaysNull() {
    assertThat(LeadingReasoningText.strip(null).text()).isNull();
  }

  @Test
  void aLeadingOpeningTagIsRecognisedEvenWithoutItsClosingTag() {
    assertThat(LeadingReasoningText.startsWithBlock("<think>")).isTrue();
    assertThat(LeadingReasoningText.startsWithBlock("\n<thought>x</thought>y")).isTrue();
    assertThat(LeadingReasoningText.startsWithBlock("Antwort <think>")).isFalse();
    assertThat(LeadingReasoningText.startsWithBlock(null)).isFalse();
  }

  /**
   * Regression guard for #2511: a recorded DeepSeek-R1 answer whose chat template put the opening
   * {@code <think>} into the prompt starts with the reasoning and carries only the closing tag.
   */
  @Test
  void reasoningBeforeALoneClosingTagIsRemoved() throws IOException {
    String recorded;
    try (InputStream in =
        getClass().getResourceAsStream("/llm/deepseek-r1-closing-tag-only.json")) {
      recorded =
          new ObjectMapper()
              .readTree(in)
              .path("choices")
              .path(0)
              .path("message")
              .path("content")
              .asString();
    }
    assertThat(recorded).doesNotContain("<think>").contains("\n</think>\n");

    LeadingReasoningText.Stripped stripped = LeadingReasoningText.strip(recorded);

    assertThat(stripped.text()).isEqualTo("Eine Week hat sieben Tage.");
    assertThat(stripped.removedCharacters())
        .isEqualTo(recorded.length() - "Eine Week hat sieben Tage.".length());
    assertThat(stripped.unclosed()).isFalse();
  }

  @Test
  void aLoneClosingTagRightAtTheStartOrSlightlyIndentedOnItsOwnLineCounts() {
    assertThat(LeadingReasoningText.strip("</think>\n\nAntwort").text()).isEqualTo("Antwort");
    LeadingReasoningText.Stripped indented =
        LeadingReasoningText.strip("Denken\n   </think>  \nAntwort");

    assertThat(indented.text()).isEqualTo("Antwort");
    assertThat(indented.withoutOpeningTag()).isTrue();
  }

  @Test
  void aClosingTagFollowedByTextOnItsLineIsAnswerText() {
    String answer = "Denken\n</think>Antwort";
    String sentence = "Ein Denkblock endet so:\n</think> beendet den Block.";

    assertThat(LeadingReasoningText.strip(answer).text()).isSameAs(answer);
    assertThat(LeadingReasoningText.strip(sentence).text()).isSameAs(sentence);
  }

  @Test
  void aClosingTagInAnIndentedCodeBlockIsAnswerText() {
    String answer = "Das Ende eines Denkblocks:\n\n    </think>\n\nDanach folgt die Antwort.";
    String tabbed = "Das Ende eines Denkblocks:\n\n\t</think>\n\nDanach folgt die Antwort.";

    assertThat(LeadingReasoningText.strip(answer).text()).isSameAs(answer);
    assertThat(LeadingReasoningText.strip(tabbed).text()).isSameAs(tabbed);
  }

  @Test
  void aClosingTagInsideATildeCodeBlockIsAnswerText() {
    String answer = "Das Ende eines Denkblocks:\n~~~\n</think>\n~~~\nDanach folgt die Antwort.";

    assertThat(LeadingReasoningText.strip(answer).text()).isSameAs(answer);
  }

  @Test
  void onlyFenceLinesOpenACodeBlock() {
    String answer = "Inline ``` ist kein Codeblock\n</think>\nAntwort";

    assertThat(LeadingReasoningText.strip(answer).text()).isEqualTo("Antwort");
  }

  @Test
  void onlyTheFirstLoneClosingTagEndsTheReasoning() {
    assertThat(LeadingReasoningText.strip("Denken\n</think>\nAntwort\n</think>\nmehr").text())
        .isEqualTo("Antwort\n</think>\nmehr");
  }

  @Test
  void aClosingTagInsideALineIsAnswerText() {
    String answer = "Das schließende Tag </think> beendet den Denktext, auch `</think>`.";

    assertThat(LeadingReasoningText.strip(answer).text()).isSameAs(answer);
  }

  @Test
  void aClosingTagAfterAnOpeningOneIsAnswerText() {
    String answer = "Ein Denkblock sieht so aus:\n<think>\nÜberlegung\n</think>\nAntwort";

    assertThat(LeadingReasoningText.strip(answer).text()).isSameAs(answer);
  }

  @Test
  void aClosingTagInsideACodeBlockIsAnswerText() {
    String answer = "Das Ende eines Denkblocks:\n```xml\n</think>\n```\nDanach folgt die Antwort.";

    assertThat(LeadingReasoningText.strip(answer).text()).isSameAs(answer);
  }

  @Test
  void aLoneClosingThoughtTagIsAnswerText() {
    String answer = "Denken\n</thought>\nAntwort";

    assertThat(LeadingReasoningText.strip(answer).text()).isSameAs(answer);
  }

  @Test
  void textWithoutAnyTagIsUnchanged() {
    String answer = "Antwort ohne Denktext\nzweite Zeile";

    LeadingReasoningText.Stripped stripped = LeadingReasoningText.strip(answer);

    assertThat(stripped.text()).isSameAs(answer);
    assertThat(stripped.removedCharacters()).isZero();
  }
}
