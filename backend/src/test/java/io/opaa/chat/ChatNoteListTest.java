package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.ChatNoteItemKind;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The two rules of the Gesprächsnotiz list (#1487): deduplication and the cap. */
class ChatNoteListTest {

  private static ChatNoteCandidate rahmen(String text) {
    return new ChatNoteCandidate(text, ChatNoteItemKind.RAHMEN);
  }

  @Test
  void aPointTheNoteAlreadyHoldsIsNotAppendedAgain() {
    List<ChatNoteCandidate> accepted =
        ChatNoteList.accept(
            List.of("Bezugsjahr 2024"), List.of(rahmen("Bezugsjahr 2024"), rahmen("Neue Angabe")));

    assertThat(accepted).containsExactly(rahmen("Neue Angabe"));
  }

  @Test
  void deduplicationIgnoresCasePunctuationAndRepeatedWhitespace() {
    List<ChatNoteCandidate> accepted =
        ChatNoteList.accept(
            List.of("Arbeitet im Bürgerbüro Nebenstelle 3"),
            List.of(rahmen("arbeitet im bürgerbüro   nebenstelle 3.")));

    assertThat(accepted).isEmpty();
  }

  @Test
  void twoIdenticalCandidatesOfOneBatchEnterOnlyOnce() {
    List<ChatNoteCandidate> accepted =
        ChatNoteList.accept(
            List.of(), List.of(rahmen("Bezugsjahr 2024"), rahmen("Bezugsjahr 2024!")));

    assertThat(accepted).containsExactly(rahmen("Bezugsjahr 2024"));
  }

  /**
   * A different kind is not a different point: the note is a list of Angaben, and the same sentence
   * twice would show up twice in the Oberfläche.
   */
  @Test
  void aDuplicateTextIsADuplicateEvenWithAnotherKind() {
    List<ChatNoteCandidate> accepted =
        ChatNoteList.accept(
            List.of("Bezugsjahr 2024"),
            List.of(new ChatNoteCandidate("Bezugsjahr 2024", ChatNoteItemKind.ANTWORTFORM)));

    assertThat(accepted).isEmpty();
  }

  @Test
  void nothingOverflowsWhileTheNoteFitsIntoItsCap() {
    assertThat(ChatNoteList.overflow(8, 2, 10)).isZero();
  }

  @Test
  void appendingBeyondTheCapDropsAsManyOldestPointsAsNeeded() {
    assertThat(ChatNoteList.overflow(10, 2, 10)).isEqualTo(2);
    assertThat(ChatNoteList.overflow(9, 2, 10)).isEqualTo(1);
  }

  /** A batch wider than the whole cap drops the note, never more than it holds. */
  @Test
  void theOverflowNeverExceedsTheCurrentSize() {
    assertThat(ChatNoteList.overflow(1, 2, 1)).isEqualTo(1);
    assertThat(ChatNoteList.overflow(0, 2, 1)).isZero();
  }
}
