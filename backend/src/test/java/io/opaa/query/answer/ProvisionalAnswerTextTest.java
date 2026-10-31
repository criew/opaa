package io.opaa.query.answer;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.query.answer.ProvisionalAnswerText.Change;
import org.junit.jupiter.api.Test;

class ProvisionalAnswerTextTest {

  @Test
  void plainTextIsAppendedChunkByChunk() {
    ProvisionalAnswerText text = new ProvisionalAnswerText();

    assertThat(text.append("Der Antrag ")).contains(new Change(false, "Der Antrag "));
    assertThat(text.append("ist fristgebunden.")).contains(new Change(false, "ist fristgebunden."));
    assertThat(text.raw()).isEqualTo("Der Antrag ist fristgebunden.");
  }

  @Test
  void aLeadingReasoningBlockIsNeverShown() {
    ProvisionalAnswerText text = new ProvisionalAnswerText();

    assertThat(text.append("<th")).isEmpty();
    assertThat(text.append("ink>Ich überlege")).isEmpty();
    assertThat(text.append(" noch.</think>\n")).isEmpty();
    assertThat(text.append("Die Frist ")).contains(new Change(false, "Die Frist "));
    assertThat(text.append("beträgt einen Monat."))
        .contains(new Change(false, "beträgt einen Monat."));
  }

  /**
   * Reasoning without an opening tag is recognised only by its closing tag: the view starts over.
   */
  @Test
  void reasoningEndingInALoneClosingTagIsReplacedOnceRecognised() {
    ProvisionalAnswerText text = new ProvisionalAnswerText();

    assertThat(text.append("Ich überlege.")).contains(new Change(false, "Ich überlege."));
    assertThat(text.append("\n</think>\n")).isEmpty();
    assertThat(text.append("Antwort.")).contains(new Change(true, "Antwort."));
    assertThat(text.append(" Weiter.")).contains(new Change(false, " Weiter."));
  }

  @Test
  void whitespaceAloneIsHeldBackUntilTextFollows() {
    ProvisionalAnswerText text = new ProvisionalAnswerText();

    assertThat(text.append("")).isEmpty();
    assertThat(text.append(null)).isEmpty();
    assertThat(text.append("  \n")).isEmpty();
    assertThat(text.append("Ja.")).contains(new Change(false, "  \nJa."));
  }
}
