package io.opaa.query.retrieval.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.indexing.metadata.MetadataFilter;
import io.opaa.query.QueryProperties;
import io.opaa.query.retrieval.RerankAvailability;
import io.opaa.query.retrieval.RetrievalContext;
import io.opaa.query.retrieval.RetrievalState;
import io.opaa.query.retrieval.StageOutcome;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * The search window and the fallback query of {@link SubQueryDecompositionStage} (#1486,
 * docs/features/conversation-memory.md, "Bauteil 1").
 */
class SubQueryDecompositionStageTest {

  private static final List<Message> THREE_TURNS =
      List.of(
          new UserMessage("Wie melde ich einen Wohnsitz an?"),
          new AssistantMessage("Die Anmeldung erfolgt im Bürgerbüro."),
          new UserMessage("Was kostet ein Anwohnerparkausweis?"),
          new AssistantMessage("Ein Anwohnerparkausweis kostet 30,70 Euro pro Jahr."),
          new UserMessage("Und wie lange ist er gültig?"),
          new AssistantMessage("Der Ausweis gilt ein Jahr."));

  private final QueryDecompositionService decomposition = mock(QueryDecompositionService.class);
  private final SubQueryDecompositionStage stage = new SubQueryDecompositionStage(decomposition);

  @Test
  void theDecompositionReceivesOnlyTheLastTurnsOfTheConversationWindow() {
    when(decomposition.decompose(any(), anyInt())).thenReturn(List.of("Teilfrage"));

    stage.apply(contextWith(THREE_TURNS, 2), RetrievalState.initial());

    assertThat(capturedContext().searchWindow())
        .extracting(Message::getText)
        .containsExactly(
            "Was kostet ein Anwohnerparkausweis?",
            "Ein Anwohnerparkausweis kostet 30,70 Euro pro Jahr.",
            "Und wie lange ist er gültig?",
            "Der Ausweis gilt ein Jahr.");
  }

  /** {@code searchWindowTurns = 0} is the documented "question only" setting. */
  @Test
  void aSearchWindowOfZeroTurnsLeavesTheDecompositionWithTheQuestionAlone() {
    when(decomposition.decompose(any(), anyInt())).thenReturn(List.of("Teilfrage"));

    stage.apply(contextWith(THREE_TURNS, 0), RetrievalState.initial());

    assertThat(capturedContext().searchWindow()).isEmpty();
    assertThat(capturedContext().question()).isEqualTo("Gilt das auch für Zweitwagen?");
  }

  /** A conversation shorter than the search window is handed over whole, not padded. */
  @Test
  void aConversationShorterThanTheSearchWindowIsHandedOverWhole() {
    when(decomposition.decompose(any(), anyInt())).thenReturn(List.of("Teilfrage"));
    List<Message> oneTurn = THREE_TURNS.subList(0, 2);

    stage.apply(contextWith(oneTurn, 2), RetrievalState.initial());

    assertThat(capturedContext().searchWindow()).isEqualTo(oneTurn);
  }

  /**
   * The fallback prepends the <b>last</b> user question of the search window, not the first: after
   * a topic change the oldest question in the window is the one the current question is least
   * likely to continue.
   */
  @Test
  void theFallbackPrependsTheLastUserQuestionOfTheSearchWindow() {
    when(decomposition.decompose(any(), anyInt())).thenReturn(List.of());

    StageOutcome outcome = stage.apply(contextWith(THREE_TURNS, 2), RetrievalState.initial());

    assertThat(outcome.state().searchQueries())
        .containsExactly("Und wie lange ist er gültig? Gilt das auch für Zweitwagen?");
  }

  /** Without a preceding turn in the search window the fallback is the question alone. */
  @Test
  void withoutAPrecedingTurnTheFallbackIsTheQuestionAlone() {
    when(decomposition.decompose(any(), anyInt())).thenReturn(List.of());

    StageOutcome outcome = stage.apply(contextWith(List.of(), 2), RetrievalState.initial());

    assertThat(outcome.state().searchQueries()).containsExactly("Gilt das auch für Zweitwagen?");
  }

  /**
   * A topic three turns back is outside the search window, so the fallback cannot drag it into the
   * search query either - the same cut governs both halves of this stage.
   */
  @Test
  void aQuestionOutsideTheSearchWindowNeverReachesTheFallbackQuery() {
    when(decomposition.decompose(any(), anyInt())).thenReturn(List.of());

    StageOutcome outcome = stage.apply(contextWith(THREE_TURNS, 1), RetrievalState.initial());

    assertThat(outcome.state().searchQueries().getFirst())
        .doesNotContain("Wohnsitz")
        .doesNotContain("Anwohnerparkausweis");
  }

  /** With the decomposition switched off no model is asked, and the fallback still cuts. */
  @Test
  void aDisabledDecompositionTakesTheSameFallbackWithoutAskingTheModel() {
    RetrievalContext context =
        new RetrievalContext(
            "Gilt das auch für Zweitwagen?",
            THREE_TURNS,
            Set.of(UUID.randomUUID()),
            MetadataFilter.NONE,
            new QueryProperties(8, 25, 1.0, 0.3, false, 3, 2, true, 0, 20, 2),
            RerankAvailability.SWITCHED_OFF);

    StageOutcome outcome = stage.apply(context, RetrievalState.initial());

    verifyNoInteractions(decomposition);
    assertThat(outcome.state().searchQueries())
        .containsExactly("Und wie lange ist er gültig? Gilt das auch für Zweitwagen?");
  }

  /**
   * The seam #1487 lives or dies on: the note's {@code RAHMEN} points must arrive as a context
   * block, so they are in {@link DecompositionContext#contextTexts()} - the anchor space of the
   * safety belt - and not merely in front of the model.
   */
  @Test
  void theRahmenPointsOfTheNoteArriveAsAnAnchoredContextBlock() {
    when(decomposition.decompose(any(), anyInt())).thenReturn(List.of("Teilfrage"));

    stage.apply(contextWith(List.of(), 2, List.of("Bezugsjahr 2024")), RetrievalState.initial());

    DecompositionContext context = capturedContext();
    assertThat(context.contextBlocks())
        .singleElement()
        .satisfies(
            block -> {
              assertThat(block.modelText())
                  .startsWith("Gesprächsnotiz")
                  .contains("Bezugsjahr 2024");
              assertThat(block.anchorTexts()).containsExactly("Bezugsjahr 2024");
            });
    assertThat(context.contextTexts()).contains("Bezugsjahr 2024");
    assertThat(context.contextTexts())
        .as("the block's heading is OPAA's own wording and anchors nothing")
        .noneSatisfy(text -> assertThat(text).contains("Gesprächsnotiz"));
  }

  /**
   * A note of nothing but ANTWORTFORM points reaches the stage as an empty {@code conversationNote}
   * and must not produce an empty heading - the decomposition then runs exactly as it does for a
   * chat without a note at all.
   */
  @Test
  void aNoteWithoutAnyRahmenPointAddsNoContextBlock() {
    when(decomposition.decompose(any(), anyInt())).thenReturn(List.of("Teilfrage"));

    stage.apply(contextWith(List.of(), 2, List.of()), RetrievalState.initial());

    assertThat(capturedContext().contextBlocks()).isEmpty();
  }

  /** Without a RAHMEN point there is no block - an empty heading would still be an instruction. */
  @Test
  void anEmptyNoteAddsNoContextBlock() {
    when(decomposition.decompose(any(), anyInt())).thenReturn(List.of("Teilfrage"));

    stage.apply(contextWith(THREE_TURNS, 2), RetrievalState.initial());

    assertThat(capturedContext().contextBlocks()).isEmpty();
  }

  private DecompositionContext capturedContext() {
    ArgumentCaptor<DecompositionContext> captor =
        ArgumentCaptor.forClass(DecompositionContext.class);
    verify(decomposition).decompose(captor.capture(), anyInt());
    return captor.getValue();
  }

  private static RetrievalContext contextWith(List<Message> window, int searchWindowTurns) {
    return contextWith(window, searchWindowTurns, List.of());
  }

  private static RetrievalContext contextWith(
      List<Message> window, int searchWindowTurns, List<String> conversationNote) {
    return new RetrievalContext(
        "Gilt das auch für Zweitwagen?",
        window,
        conversationNote,
        Set.of(UUID.randomUUID()),
        MetadataFilter.NONE,
        new QueryProperties(8, 25, 1.0, 0.3, true, 3, 2, true, 0, 20, searchWindowTurns),
        RerankAvailability.SWITCHED_OFF);
  }
}
