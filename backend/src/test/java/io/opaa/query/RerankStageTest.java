package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.indexing.metadata.MetadataFilter;
import io.opaa.llm.RerankClient.ScoredCandidate;
import io.opaa.llm.RerankModelRole;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;

/**
 * {@link RerankStage} on its own: what it does with a usable model role, what it does without one,
 * and the invariant that matters most - it restores the {@code top-k} cap on every path, because
 * {@link RankFusionStage} widens its budget for it.
 */
class RerankStageTest {

  private static final int TOP_K = 8;

  private final RerankModelRole role = mock(RerankModelRole.class);
  private final RerankStage stage = new RerankStage(role);

  private static QueryProperties properties(int rerankCandidateCount) {
    return new QueryProperties(TOP_K, 25, 1.0, 0.3, 1.0, false, 3, 2, true, rerankCandidateCount);
  }

  private static RetrievalContext context(
      int rerankCandidateCount, RerankAvailability availability) {
    return new RetrievalContext(
        "Wie hoch ist die Verwaltungsgebühr?",
        List.of(),
        Set.of(UUID.randomUUID()),
        MetadataFilter.NONE,
        properties(rerankCandidateCount),
        availability);
  }

  private static Document chunk(String id) {
    return Document.builder()
        .id(id)
        .text("Text " + id)
        .metadata(Map.of("document_id", "doc-" + id, "file_name", id + ".md"))
        .build();
  }

  private static RetrievalState stateWith(int chunkCount) {
    List<Document> documents =
        IntStream.range(0, chunkCount).mapToObj(i -> chunk("c" + i)).toList();
    return RetrievalState.initial()
        .withCandidateLists(
            List.of(new CandidateList(RankFusionStage.FUSED_LIST_LABEL, documents)));
  }

  /**
   * A switched-off role and a broken one are different statements about an installation, and the
   * protocol must not report both as the same thing.
   */
  @Test
  void aSwitchedOffRoleIsDisabledAndPassesTheInputThroughUnchanged() {
    RetrievalState state = stateWith(TOP_K);

    StageOutcome outcome = stage.apply(context(50, RerankAvailability.SWITCHED_OFF), state);

    assertThat(outcome.state().selection()).isEqualTo(state.selection());
    assertThat(outcome.explanation().status()).isEqualTo(StageStatus.DISABLED);
    assertThat(outcome.explanation().notes().get(0)).contains("OPAA_RERANK_ENABLED");
    verify(role, never()).rerank(anyString(), any());
  }

  @Test
  void aRoleThatIsOnButNotUsableIsUnavailableRatherThanDisabled() {
    RetrievalState state = stateWith(TOP_K);

    StageOutcome outcome = stage.apply(context(50, RerankAvailability.NOT_USABLE), state);

    assertThat(outcome.state().selection()).isEqualTo(state.selection());
    assertThat(outcome.explanation().status()).isEqualTo(StageStatus.UNAVAILABLE);
    assertThat(outcome.explanation().notes().get(0)).contains("switched on but was not usable");
    verify(role, never()).rerank(anyString(), any());
  }

  /**
   * "Switched off is the identity" (RetrievalStageName) down to the candidate lists: with rank
   * fusion switched off too, several labelled lists are still in flight when this stage is reached,
   * and collapsing them into one fused-labelled list would tell the diagnosis that a fusion
   * happened which did not.
   */
  @Test
  void aSwitchedOffStageLeavesSeveralListsAndTheirLabelsAlone() {
    RetrievalState state =
        RetrievalState.initial()
            .withCandidateLists(
                List.of(
                    new CandidateList(VectorSearchStage.listLabel(0), List.of(chunk("v0"))),
                    new CandidateList(FullTextSearchStage.listLabel(0), List.of(chunk("f0")))));

    StageOutcome outcome = stage.apply(context(50, RerankAvailability.SWITCHED_OFF), state);

    assertThat(outcome.state().candidateLists())
        .extracting(CandidateList::label)
        .containsExactly(VectorSearchStage.listLabel(0), FullTextSearchStage.listLabel(0));
    assertThat(outcome.explanation().incomingCount()).isEqualTo(2);
    assertThat(outcome.explanation().outgoingCount()).isEqualTo(2);
  }

  @Test
  void aZeroCandidateWindowSwitchesTheStageOffThroughItsOwnParameter() {
    StageOutcome outcome = stage.apply(context(0, RerankAvailability.USABLE), stateWith(TOP_K));

    assertThat(outcome.explanation().status()).isEqualTo(StageStatus.DISABLED);
    assertThat(outcome.explanation().notes().get(0)).contains("rerank-candidate-count=0");
    verify(role, never()).rerank(anyString(), any());
  }

  @Test
  void reordersTheWindowByTheModelsScoresAndCapsAtTopK() {
    // 20 fused candidates, reversed by the reranker: the last chunk must end up first.
    when(role.rerank(anyString(), any()))
        .thenReturn(
            IntStream.range(0, 20).mapToObj(i -> new ScoredCandidate(i, i)).toList().reversed());

    StageOutcome outcome = stage.apply(context(50, RerankAvailability.USABLE), stateWith(20));

    List<Document> selection = outcome.state().selection();
    assertThat(selection).hasSize(TOP_K);
    assertThat(selection.get(0).getId()).isEqualTo("c19");
    assertThat(selection.get(TOP_K - 1).getId()).isEqualTo("c12");
    assertThat(outcome.explanation().status()).isEqualTo(StageStatus.EXECUTED);
    assertThat(outcome.explanation().incomingCount()).isEqualTo(20);
    assertThat(outcome.explanation().outgoingCount()).isEqualTo(TOP_K);
  }

  /** Every incoming candidate gets a verdict, so none can vanish from the diagnosis. */
  @Test
  void everyIncomingCandidateGetsAVerdict() {
    when(role.rerank(anyString(), any()))
        .thenReturn(IntStream.range(0, 12).mapToObj(i -> new ScoredCandidate(i, -i)).toList());

    StageOutcome outcome = stage.apply(context(12, RerankAvailability.USABLE), stateWith(20));

    assertThat(outcome.explanation().verdicts()).hasSize(20);
    assertThat(outcome.explanation().verdicts())
        .filteredOn(v -> v.outcome() == CandidateOutcome.KEPT)
        .hasSize(TOP_K);
    assertThat(outcome.explanation().verdicts())
        .filteredOn(v -> v.reason() == VerdictReason.OUTSIDE_RERANK_BUDGET)
        .hasSize(12);
  }

  /** Only the window is sent; candidates behind it were never scored and are out. */
  @Test
  void onlyTheCandidateWindowIsSentToTheModel() {
    when(role.rerank(anyString(), any()))
        .thenReturn(List.of(new ScoredCandidate(0, 1.0), new ScoredCandidate(1, 0.5)));

    stage.apply(context(2, RerankAvailability.USABLE), stateWith(20));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> sent = ArgumentCaptor.forClass(List.class);
    verify(role).rerank(anyString(), sent.capture());
    assertThat(sent.getValue()).containsExactly("Text c0", "Text c1");
  }

  /**
   * The invariant this stage exists to keep: fusion widened its budget to the candidate window, so
   * a failed call must not leave 50 chunks in the selection.
   */
  @Test
  void aFailedCallStillRestoresTheTopKCap() {
    when(role.rerank(anyString(), any())).thenReturn(List.of());

    StageOutcome outcome = stage.apply(context(50, RerankAvailability.USABLE), stateWith(50));

    assertThat(outcome.state().selection()).hasSize(TOP_K);
    assertThat(outcome.state().selection().get(0).getId()).isEqualTo("c0");
    assertThat(outcome.explanation().status()).isEqualTo(StageStatus.UNAVAILABLE);
    assertThat(outcome.explanation().outgoingCount()).isEqualTo(TOP_K);
  }

  /** A partial answer must not make the unscored rest of the window disappear. */
  @Test
  void candidatesTheModelDidNotScoreKeepTheirFusedOrderBehindTheScoredOnes() {
    when(role.rerank(anyString(), any())).thenReturn(List.of(new ScoredCandidate(3, 9.0)));

    StageOutcome outcome = stage.apply(context(50, RerankAvailability.USABLE), stateWith(5));

    assertThat(outcome.state().selection())
        .extracting(Document::getId)
        .containsExactly("c3", "c0", "c1", "c2", "c4");
  }

  /**
   * A candidate window below {@code top-k} must reorder its window without shrinking the answer:
   * everything behind the window keeps its fused position behind the reranked ones.
   */
  @Test
  void aWindowSmallerThanTopKDoesNotShrinkTheSelection() {
    when(role.rerank(anyString(), any()))
        .thenReturn(List.of(new ScoredCandidate(2, 9.0), new ScoredCandidate(0, 1.0)));

    StageOutcome outcome = stage.apply(context(3, RerankAvailability.USABLE), stateWith(12));

    assertThat(outcome.state().selection()).hasSize(TOP_K);
    assertThat(outcome.state().selection())
        .extracting(Document::getId)
        .startsWith("c2", "c0", "c1", "c3", "c4");
  }

  /**
   * With a usable role and nothing to rerank, the note must say exactly that - reporting "the role
   * is not usable" would be a false statement about the installation.
   */
  @Test
  void anEmptySelectionIsPassedThroughWithItsOwnNoteAndWithoutCallingTheModel() {
    StageOutcome outcome =
        stage.apply(context(50, RerankAvailability.USABLE), RetrievalState.initial());

    assertThat(outcome.state().selection()).isEmpty();
    assertThat(outcome.explanation().status()).isEqualTo(StageStatus.EXECUTED);
    assertThat(outcome.explanation().notes().get(0)).contains("nothing to rerank");
    verify(role, never()).rerank(anyString(), any());
  }
}
