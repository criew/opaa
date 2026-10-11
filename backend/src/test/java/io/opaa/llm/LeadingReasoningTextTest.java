package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Only leading {@code <think>}/{@code <thought>} blocks are reasoning; the rest is the answer. */
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
}
