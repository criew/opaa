package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The rendered Gesprächsnotiz block both prompts share (#1487). */
class ConversationNoteBlockTest {

  @Test
  void everyPointIsRenderedAsItsOwnBulletUnderTheHeader() {
    ConversationNoteBlock block =
        ConversationNoteBlock.render(
            List.of("Arbeitet im Bürgerbüro Nebenstelle 3", "Bezugsjahr 2024"));

    assertThat(block.modelText())
        .isEqualTo(
            ConversationNoteBlock.HEADER
                + "\n- Arbeitet im Bürgerbüro Nebenstelle 3"
                + "\n- Bezugsjahr 2024");
  }

  /** The header denies the block the status of a source - a note point is never citable. */
  @Test
  void theHeaderSaysThatTheBlockIsNoSource() {
    assertThat(ConversationNoteBlock.render(List.of("Bezugsjahr 2024")).modelText())
        .startsWith(ConversationNoteBlock.HEADER)
        .contains("kein Beleg, keine Quelle");
  }

  /**
   * The two halves of one rendering (#1487): the model sees the heading, the safety belt only the
   * points. A heading word in the anchor space would relate a degenerate sub-query
   * ("Personalausweis") to any chat that merely carries a note - see this class's subject.
   */
  @Test
  void theAnchorTextsAreThePointsWithoutTheHeading() {
    ConversationNoteBlock block =
        ConversationNoteBlock.render(List.of("Bezugsjahr 2024", "Möchte knappe Antworten"));

    assertThat(block.anchorTexts()).containsExactly("Bezugsjahr 2024", "Möchte knappe Antworten");
    assertThat(block.anchorTexts()).noneSatisfy(text -> assertThat(text).contains("Person"));
    assertThat(block.modelText())
        .as("the heading does reach the model, it just anchors nothing")
        .contains("Person");
  }

  /** Without a point there is no block at all - an empty heading would still be an instruction. */
  @Test
  void anEmptyNoteRendersNoBlock() {
    assertThat(ConversationNoteBlock.render(List.of())).isNull();
  }
}
