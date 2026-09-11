package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The rendered Gesprächsnotiz block both prompts share (#1487). */
class ConversationNoteBlockTest {

  @Test
  void everyPointIsRenderedAsItsOwnBulletUnderTheHeader() {
    String block =
        ConversationNoteBlock.render(
            List.of("Arbeitet im Bürgerbüro Nebenstelle 3", "Bezugsjahr 2024"));

    assertThat(block)
        .isEqualTo(
            ConversationNoteBlock.HEADER
                + "\n- Arbeitet im Bürgerbüro Nebenstelle 3"
                + "\n- Bezugsjahr 2024");
  }

  /** The header denies the block the status of a source - a note point is never citable. */
  @Test
  void theHeaderSaysThatTheBlockIsNoSource() {
    assertThat(ConversationNoteBlock.render(List.of("Bezugsjahr 2024")))
        .startsWith(ConversationNoteBlock.HEADER)
        .contains("kein Beleg, keine Quelle");
  }

  /** Without a point there is no block at all - an empty heading would still be an instruction. */
  @Test
  void anEmptyNoteRendersNoBlock() {
    assertThat(ConversationNoteBlock.render(List.of())).isNull();
  }
}
