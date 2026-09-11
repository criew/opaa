package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.ChatNoteItemKind;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The defensive parsing of the Gesprächsnotiz condensation (#1487): what the note keeps of an
 * answer that follows the prompt's format - and of one that does not.
 */
class ChatNoteExtractionTest {

  @Test
  void oneLinePerPointWithItsKindPrefix() {
    List<ChatNoteCandidate> candidates =
        ChatNoteExtraction.parse(
            "RAHMEN: Arbeitet im Bürgerbüro Nebenstelle 3\nANTWORTFORM: Möchte knappe Antworten");

    assertThat(candidates)
        .containsExactly(
            new ChatNoteCandidate("Arbeitet im Bürgerbüro Nebenstelle 3", ChatNoteItemKind.RAHMEN),
            new ChatNoteCandidate("Möchte knappe Antworten", ChatNoteItemKind.ANTWORTFORM));
  }

  /**
   * The specification's "Zeile ohne erkennbare Art" rule: ANTWORTFORM, the kind that never reaches
   * the search, where a superfluous point does the least damage. The unrecognized prefix stays part
   * of the text rather than being silently cut - cutting it would lose content on a colon the
   * person's own sentence contains.
   */
  @Test
  void aLineWithoutARecognizableKindBecomesAntwortform() {
    assertThat(ChatNoteExtraction.parse("Bezugsjahr 2024"))
        .containsExactly(new ChatNoteCandidate("Bezugsjahr 2024", ChatNoteItemKind.ANTWORTFORM));
    assertThat(ChatNoteExtraction.parse("KONTEXT: Bezugsjahr 2024"))
        .containsExactly(
            new ChatNoteCandidate("KONTEXT: Bezugsjahr 2024", ChatNoteItemKind.ANTWORTFORM));
  }

  @Test
  void theKindPrefixIsReadRegardlessOfCaseAndSurroundingPunctuation() {
    assertThat(ChatNoteExtraction.parse("- rahmen : Bezugsjahr 2024"))
        .containsExactly(new ChatNoteCandidate("Bezugsjahr 2024", ChatNoteItemKind.RAHMEN));
  }

  @Test
  void theSentinelMeansNoPointAtAll() {
    assertThat(ChatNoteExtraction.parse("KEINE")).isEmpty();
    assertThat(ChatNoteExtraction.parse("keine.")).isEmpty();
    assertThat(ChatNoteExtraction.parse("KEINE\nRAHMEN: Bezugsjahr 2024"))
        .as("the sentinel ends the answer - what follows it is not an Angabe of the person")
        .isEmpty();
  }

  @Test
  void anEmptyOrUnusableAnswerYieldsNoPoint() {
    assertThat(ChatNoteExtraction.parse(null)).isEmpty();
    assertThat(ChatNoteExtraction.parse("   ")).isEmpty();
    assertThat(ChatNoteExtraction.parse("RAHMEN:\nRAHMEN:   ")).isEmpty();
  }

  /**
   * The fixed two-per-turn bound (specification, "Lebenszyklus"): without it a talkative message
   * would push the Rahmenangabe of turn 1 over the note's cap - the very case {@code
   * constraint_carryover} stands for.
   */
  @Test
  void moreThanTwoLinesPerTurnKeepOnlyTheFirstTwo() {
    List<ChatNoteCandidate> candidates =
        ChatNoteExtraction.parse(
            """
            RAHMEN: Erste Angabe
            RAHMEN: Zweite Angabe
            RAHMEN: Dritte Angabe
            ANTWORTFORM: Vierte Angabe
            """);

    assertThat(candidates).hasSize(ChatNoteExtraction.MAX_POINTS_PER_TURN);
    assertThat(candidates)
        .extracting(ChatNoteCandidate::text)
        .containsExactly("Erste Angabe", "Zweite Angabe");
  }

  @Test
  void aBulletOrNumberingMarkerIsStripped() {
    assertThat(ChatNoteExtraction.parse("1. RAHMEN: Bezugsjahr 2024\n* ANTWORTFORM: Knapp"))
        .containsExactly(
            new ChatNoteCandidate("Bezugsjahr 2024", ChatNoteItemKind.RAHMEN),
            new ChatNoteCandidate("Knapp", ChatNoteItemKind.ANTWORTFORM));
  }

  @Test
  void aPointLongerThanTheMaximumIsShortenedWithAnEllipsis() {
    String tooLong = "RAHMEN: " + "a".repeat(ChatNoteExtraction.MAX_TEXT_LENGTH + 50);

    List<ChatNoteCandidate> candidates = ChatNoteExtraction.parse(tooLong);

    assertThat(candidates).hasSize(1);
    assertThat(candidates.getFirst().text()).hasSize(ChatNoteExtraction.MAX_TEXT_LENGTH);
    assertThat(candidates.getFirst().text()).endsWith("…");
  }

  @Test
  void aPointOfExactlyTheMaximumLengthIsKeptVerbatim() {
    String exact = "a".repeat(ChatNoteExtraction.MAX_TEXT_LENGTH);

    assertThat(ChatNoteExtraction.parse("RAHMEN: " + exact))
        .containsExactly(new ChatNoteCandidate(exact, ChatNoteItemKind.RAHMEN));
  }

  /**
   * The prompt asks for exactly the kind names the parser recognizes - a translation on either side
   * would silently make every line an ANTWORTFORM one.
   */
  @Test
  void thePromptNamesBothKindsAndTheSentinelAndCarriesTheMessage() {
    String prompt = ChatNoteExtraction.prompt("Ich arbeite in der Nebenstelle 3.");

    assertThat(prompt)
        .contains(ChatNoteItemKind.RAHMEN.name())
        .contains(ChatNoteItemKind.ANTWORTFORM.name())
        .contains(ChatNoteExtraction.NOTHING_SENTINEL)
        .contains("Ich arbeite in der Nebenstelle 3.")
        .contains(String.valueOf(ChatNoteExtraction.MAX_TEXT_LENGTH))
        .contains(String.valueOf(ChatNoteExtraction.MAX_POINTS_PER_TURN));
  }
}
