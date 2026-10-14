package io.opaa.retrieval.ranking;

import static io.opaa.retrieval.RetrievalPipelineTestSupport.chunk;
import static io.opaa.retrieval.RetrievalPipelineTestSupport.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.CandidateOutcome;
import io.opaa.api.types.VerdictReason;
import io.opaa.llm.RerankClient.ScoredCandidate;
import io.opaa.llm.RerankModelRole;
import io.opaa.metadata.MetadataFilter;
import io.opaa.retrieval.CandidateList;
import io.opaa.retrieval.CandidateVerdict;
import io.opaa.retrieval.FusionMethod;
import io.opaa.retrieval.QueryProperties;
import io.opaa.retrieval.RelevanceCutoffProperties;
import io.opaa.retrieval.RelevanceCutoffProperties.Mode;
import io.opaa.retrieval.RerankAvailability;
import io.opaa.retrieval.RetrievalContext;
import io.opaa.retrieval.RetrievalListLabel;
import io.opaa.retrieval.RetrievalState;
import io.opaa.retrieval.SearchPath;
import io.opaa.retrieval.StageOutcome;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.document.Document;

/**
 * The guaranteed top candidates of every list ({@code opaa.query.fusion.guaranteed-per-list},
 * docs/handbuch/suche.md, Stufe 7): they reach the selection in every fusion mode, the relevance
 * cutoff and document completion never remove them, a failed rerank keeps them, and {@code 0} is
 * the fusion without the guarantee.
 */
class GuaranteedListTopTest {

  private static final UUID LIBRARY_ID = UUID.randomUUID();
  private static final int TOP_K = 10;

  private static QueryProperties properties(
      int topK, FusionMethod method, boolean quota, int guaranteed) {
    return new QueryProperties(topK, 25, 1.0, 0.3, false, 3, 1, true, 50, 20, 2)
        .withFusion(new QueryProperties.Fusion(method, quota, 0.7, guaranteed));
  }

  static Stream<Arguments> everyFusionMode() {
    return Stream.of(
        Arguments.of(FusionMethod.RRF, true),
        Arguments.of(FusionMethod.RRF, false),
        Arguments.of(FusionMethod.SCORE, true),
        Arguments.of(FusionMethod.SCORE, false));
  }

  /**
   * Twelve chunks both lists lead in the same order around rank 10, and one chunk only the lexical
   * list knows - at its rank 1. Under every fusion the shared chunks outrank it: two contributions
   * against one.
   */
  private static RetrievalState sharedAgainstSoloLeader() {
    List<Document> vector = new ArrayList<>();
    List<Document> lexical = new ArrayList<>();
    lexical.add(chunk("solo", "doc-solo", 0.30));
    for (int i = 1; i <= 12; i++) {
      vector.add(chunk("s" + i, "doc-s" + i, 0.95 - i * 0.01));
      lexical.add(chunk("s" + i, "doc-s" + i, 0.29 - i * 0.01));
    }
    return RetrievalState.initial()
        .withSearchResults(
            List.of(
                new CandidateList("vector search · sub-query 1", vector, SearchPath.VECTOR, 1),
                new CandidateList(
                    "full-text search · sub-query 1", lexical, SearchPath.FULL_TEXT, 1)));
  }

  private static StageOutcome fuse(QueryProperties properties, RetrievalState state) {
    return new RankFusionStage().apply(context(Set.of(LIBRARY_ID), properties), state);
  }

  private static List<String> ids(StageOutcome outcome) {
    return outcome.state().selection().stream().map(Document::getId).toList();
  }

  @ParameterizedTest
  @MethodSource("everyFusionMode")
  void aRankOneHitOfOnlyOneListReachesTheSelectionAgainstChunksBothListsLead(
      FusionMethod method, boolean quota) {
    StageOutcome without = fuse(properties(TOP_K, method, quota, 0), sharedAgainstSoloLeader());
    StageOutcome with = fuse(properties(TOP_K, method, quota, 1), sharedAgainstSoloLeader());

    assertThat(ids(without)).doesNotContain("solo").hasSize(TOP_K);
    assertThat(ids(with)).contains("solo").hasSize(TOP_K);
    assertThat(ids(with))
        .containsExactly("s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "solo");
    assertThat(with.state().guaranteedChunkIds()).containsExactly("s1", "solo");
    assertThat(with.explanation().verdicts())
        .filteredOn(verdict -> List.of("solo", "s10", "s11").contains(verdict.chunkId()))
        .extracting(CandidateVerdict::chunkId, CandidateVerdict::outcome, CandidateVerdict::reason)
        .containsExactlyInAnyOrder(
            tuple("solo", CandidateOutcome.KEPT, VerdictReason.GUARANTEED_LIST_TOP),
            tuple("s10", CandidateOutcome.DROPPED, VerdictReason.DISPLACED_BY_GUARANTEED_LIST_TOP),
            tuple("s11", CandidateOutcome.DROPPED, VerdictReason.OUTSIDE_FUSION_BUDGET));
    assertThat(with.explanation().notes())
        .anySatisfy(
            note -> assertThat(note).contains("top 1 candidate(s) of every list guaranteed"));
  }

  /** {@code 0} must be the fusion without the guarantee, down to every verdict and every note. */
  @ParameterizedTest
  @MethodSource("everyFusionMode")
  void zeroGuaranteedIsTheHeadOfTheFusedOrder(FusionMethod method, boolean quota) {
    QueryProperties properties = properties(TOP_K, method, quota, 0);
    RetrievalState state = sharedAgainstSoloLeader();
    List<CandidateFusion.FusedCandidate> fused =
        CandidateFusion.fuse(state.candidateLists(), properties.fusion(), 0.3);

    StageOutcome outcome = fuse(properties, state);

    assertThat(ids(outcome))
        .containsExactlyElementsOf(
            fused.stream().limit(TOP_K).map(candidate -> candidate.document().getId()).toList());
    assertThat(outcome.state().guaranteedChunkIds()).isEmpty();
    for (int i = 0; i < fused.size(); i++) {
      CandidateVerdict verdict = outcome.explanation().verdicts().get(i);
      assertThat(verdict.chunkId()).isEqualTo(fused.get(i).document().getId());
      assertThat(verdict.rank()).isEqualTo(i + 1);
      assertThat(verdict.reason())
          .isEqualTo(i < TOP_K ? VerdictReason.WITHIN_BUDGET : VerdictReason.OUTSIDE_FUSION_BUDGET);
    }
    assertThat(outcome.explanation().notes()).noneMatch(note -> note.contains("guaranteed"));
  }

  /** A chunk at the top of several lists is one guaranteed candidate, not two places. */
  @Test
  void aChunkLeadingSeveralListsIsGuaranteedOnce() {
    List<Document> vector = List.of(chunk("a", "doc-a", 0.9), chunk("b", "doc-b", 0.8));
    List<Document> lexical = List.of(chunk("a", "doc-a", 0.5), chunk("c", "doc-c", 0.4));
    RetrievalState state =
        RetrievalState.initial()
            .withSearchResults(
                List.of(
                    new CandidateList("vector search · sub-query 1", vector, SearchPath.VECTOR, 1),
                    new CandidateList(
                        "full-text search · sub-query 1", lexical, SearchPath.FULL_TEXT, 1)));

    StageOutcome outcome = fuse(properties(2, FusionMethod.RRF, true, 1), state);

    assertThat(outcome.state().guaranteedChunkIds()).containsExactly("a");
    assertThat(ids(outcome)).hasSize(2).startsWith("a");
    assertThat(outcome.explanation().notes())
        .anySatisfy(note -> assertThat(note).contains("1 distinct chunk(s)"));
  }

  /**
   * Six lists with a guaranteed leader each and a budget of four: the budget stays the cap, and the
   * leaders earliest in the fused order win - the same ones on every run.
   */
  @Test
  void moreGuaranteedCandidatesThanBudgetKeepTheCapAndTheEarliestInFusedOrderWin() {
    List<CandidateList> lists = new ArrayList<>();
    for (int q = 1; q <= 3; q++) {
      List<Document> shared = new ArrayList<>();
      for (int i = 1; i <= 6; i++) {
        shared.add(chunk("q" + q + "-s" + i, "doc-q" + q + "-s" + i, 0.9 - i * 0.01));
      }
      List<Document> vector = new ArrayList<>(List.of(chunk("v" + q, "doc-v" + q, 0.99)));
      vector.addAll(shared);
      List<Document> lexical = new ArrayList<>(List.of(chunk("l" + q, "doc-l" + q, 0.99)));
      lexical.addAll(shared);
      lists.add(new CandidateList("vector search · sub-query " + q, vector, SearchPath.VECTOR, q));
      lists.add(
          new CandidateList("full-text search · sub-query " + q, lexical, SearchPath.FULL_TEXT, q));
    }
    RetrievalState state = RetrievalState.initial().withSearchResults(lists);
    QueryProperties properties = properties(4, FusionMethod.RRF, true, 1);

    StageOutcome first = fuse(properties, state);
    StageOutcome second = fuse(properties, state);

    assertThat(ids(first)).hasSize(4).allMatch(id -> id.startsWith("v") || id.startsWith("l"));
    assertThat(ids(second)).isEqualTo(ids(first));
    assertThat(second.explanation().verdicts()).isEqualTo(first.explanation().verdicts());
    List<String> fusedLeaders =
        CandidateFusion.fuse(lists, properties.fusion(), 0.3).stream()
            .map(candidate -> candidate.document().getId())
            .filter(id -> id.startsWith("v") || id.startsWith("l"))
            .limit(4)
            .toList();
    assertThat(ids(first)).containsExactlyElementsOf(fusedLeaders);
  }

  /**
   * The cutoff measures against the best score of all sub-queries; it must not undo the guarantee.
   */
  @Test
  void theRelevanceCutoffNeverRemovesAGuaranteedCandidate() {
    List<Document> documents = new ArrayList<>();
    Map<String, Double> scores = new HashMap<>();
    double[] values = {1.0, 0.9, 0.8, 0.2, 0.1};
    for (int i = 0; i < values.length; i++) {
      String id = String.valueOf((char) ('a' + i));
      documents.add(chunk(id, "doc-" + id, 0.5));
      scores.put(id, values[i]);
    }
    RetrievalState state =
        RetrievalState.initial()
            .withCandidateLists(
                List.of(new CandidateList(RetrievalListLabel.FUSED_LIST_LABEL, documents)))
            .withSelectionScores(scores)
            .withGuaranteedChunkIds(Set.of("e"));

    StageOutcome outcome =
        new RelevanceCutoffStage(new RelevanceCutoffProperties(Mode.RELATIVE, 2, 0.6, 0.25))
            .apply(context(Set.of(LIBRARY_ID), properties(8, FusionMethod.RRF, true, 1)), state);

    assertThat(ids(outcome)).containsExactly("a", "b", "c", "e");
    assertThat(outcome.state().relevanceCut().removedChunkIds()).containsExactly("d");
    assertThat(outcome.explanation().verdicts())
        .extracting(CandidateVerdict::chunkId, CandidateVerdict::reason)
        .contains(
            tuple("d", VerdictReason.BELOW_RELEVANCE_CUTOFF),
            tuple("e", VerdictReason.GUARANTEED_LIST_TOP));
    assertThat(outcome.explanation().notes())
        .anySatisfy(note -> assertThat(note).contains("1 guaranteed top candidate(s)"));
  }

  /** Tier 2 would take the lowest-ranked chunk - here the guaranteed one, which must stay. */
  @Test
  void documentCompletionNeverEvictsAGuaranteedCandidate() {
    Document docA = chunk("a-0", "doc-a", 0.9);
    Document docB = chunk("b-0", "doc-b", 0.8);
    Document docC = chunk("c-0", "doc-c", 0.7);
    Document sibling = chunk("a-1", "doc-a", 0.6);
    List<Document> selection = List.of(docA, docB, docC);
    List<Document> pool = List.of(docA, docB, docC, sibling);

    List<Document> unprotected =
        DocumentCompletion.complete(selection, pool, 2, 3, Set.of(), new ArrayList<>());
    List<Document> protectedRun =
        DocumentCompletion.complete(selection, pool, 2, 3, Set.of("c-0"), new ArrayList<>());

    assertThat(unprotected).extracting(Document::getId).containsExactly("a-0", "b-0", "a-1");
    assertThat(protectedRun).extracting(Document::getId).containsExactly("a-0", "c-0", "a-1");
  }

  /**
   * A rerank that scores nothing falls back to the selection of a run without reranking - which
   * holds the guaranteed candidate even when the widened fusion budget placed it beyond top-k.
   */
  @Test
  void aFailedRerankKeepsTheGuaranteedCandidatesAsTheFusionWouldHave() {
    RerankModelRole role = mock(RerankModelRole.class);
    when(role.rerank(anyString(), any())).thenReturn(List.of());
    QueryProperties properties = properties(TOP_K, FusionMethod.RRF, true, 1);
    RetrievalContext reranking =
        new RetrievalContext(
            "Frage",
            List.of(),
            Set.of(LIBRARY_ID),
            MetadataFilter.NONE,
            properties,
            RerankAvailability.USABLE);

    StageOutcome fused = new RankFusionStage().apply(reranking, sharedAgainstSoloLeader());
    StageOutcome reranked = new RerankStage(role).apply(reranking, fused.state());
    StageOutcome withoutReranking = fuse(properties, sharedAgainstSoloLeader());

    assertThat(ids(fused)).hasSize(13);
    assertThat(ids(reranked)).containsExactlyElementsOf(ids(withoutReranking)).contains("solo");
  }

  /** The stage hands the fusion's guaranteed chunks to the completion, so tier 2 spares them. */
  @Test
  void theDocumentCompletionStagePassesTheGuaranteedChunksOn() {
    Document docA = chunk("a-0", "doc-a", 0.9);
    Document docB = chunk("b-0", "doc-b", 0.8);
    Document docC = chunk("c-0", "doc-c", 0.7);
    Document sibling = chunk("a-1", "doc-a", 0.6);
    RetrievalState state =
        RetrievalState.initial()
            .withSearchResults(
                List.of(
                    new CandidateList(
                        "vector search · sub-query 1",
                        List.of(docA, docB, docC, sibling),
                        SearchPath.VECTOR,
                        1)))
            .withCandidateLists(
                List.of(
                    new CandidateList(
                        RetrievalListLabel.FUSED_LIST_LABEL, List.of(docA, docB, docC))))
            .withGuaranteedChunkIds(Set.of("c-0"));
    QueryProperties completing = new QueryProperties(3, 25, 1.0, 0.3, false, 3, 2, true, 50, 20, 2);

    StageOutcome outcome =
        new DocumentCompletionStage().apply(context(Set.of(LIBRARY_ID), completing), state);

    assertThat(ids(outcome)).containsExactly("a-0", "c-0", "a-1");
  }

  /**
   * A rerank that scores is not bound by the guarantee: it may put a top candidate behind top-k.
   */
  @Test
  void anAnsweringRerankMayPutAGuaranteedCandidateBehindTopK() {
    RerankModelRole role = mock(RerankModelRole.class);
    List<ScoredCandidate> scored = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      scored.add(new ScoredCandidate(i, 1.0 - i * 0.01));
    }
    scored.add(new ScoredCandidate(12, 0.01));
    when(role.rerank(anyString(), any())).thenReturn(scored);
    RetrievalContext reranking =
        new RetrievalContext(
            "Frage",
            List.of(),
            Set.of(LIBRARY_ID),
            MetadataFilter.NONE,
            properties(TOP_K, FusionMethod.RRF, true, 1),
            RerankAvailability.USABLE);

    StageOutcome fused = new RankFusionStage().apply(reranking, sharedAgainstSoloLeader());
    StageOutcome reranked = new RerankStage(role).apply(reranking, fused.state());

    assertThat(ids(fused)).contains("solo");
    assertThat(ids(reranked)).hasSize(TOP_K).doesNotContain("solo");
    assertThat(reranked.explanation().verdicts())
        .filteredOn(verdict -> verdict.chunkId().equals("solo"))
        .extracting(CandidateVerdict::outcome, CandidateVerdict::reason)
        .containsExactly(tuple(CandidateOutcome.DROPPED, VerdictReason.OUTSIDE_RERANK_BUDGET));
  }
}
