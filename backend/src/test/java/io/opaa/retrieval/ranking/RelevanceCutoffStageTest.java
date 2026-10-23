package io.opaa.retrieval.ranking;

import static io.opaa.retrieval.RetrievalPipelineTestSupport.chunk;
import static io.opaa.retrieval.RetrievalPipelineTestSupport.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.CandidateOutcome;
import io.opaa.api.types.RetrievalStageName;
import io.opaa.api.types.VerdictReason;
import io.opaa.llm.RerankModelRole;
import io.opaa.retrieval.CandidateList;
import io.opaa.retrieval.CandidateVerdict;
import io.opaa.retrieval.QueryProperties;
import io.opaa.retrieval.RelevanceCutoffProperties;
import io.opaa.retrieval.RelevanceCutoffProperties.Mode;
import io.opaa.retrieval.RetrievalListLabel;
import io.opaa.retrieval.RetrievalPipelineProperties;
import io.opaa.retrieval.RetrievalPipelineResult;
import io.opaa.retrieval.RetrievalPipelineTestSupport;
import io.opaa.retrieval.RetrievalState;
import io.opaa.retrieval.StageExplanation;
import io.opaa.retrieval.StageOutcome;
import io.opaa.retrieval.StageStatus;
import io.opaa.retrieval.search.FullTextChunkSearch;
import io.opaa.retrieval.search.FullTextSearchResult;
import io.opaa.retrieval.search.QueryDecompositionService;
import io.opaa.retrieval.search.VectorChunkSearch;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

/**
 * The relevance cutoff (docs/handbuch/spaces/suche.md, Stufe 9): it shortens the selection by
 * score, never below the minimum, never reorders, and names why it stopped.
 */
class RelevanceCutoffStageTest {

  private static final UUID LIBRARY_ID = UUID.randomUUID();
  private static final QueryProperties PROPERTIES =
      new QueryProperties(8, 25, 1.0, 0.3, false, 3, 1, true, 50, 20, 2);

  /** A fused selection a..e with the given scores, as fusion hands it on. */
  private static RetrievalState selectionWithScores(double... scores) {
    List<Document> documents = new java.util.ArrayList<>();
    Map<String, Double> byId = new HashMap<>();
    for (int i = 0; i < scores.length; i++) {
      String id = String.valueOf((char) ('a' + i));
      documents.add(chunk(id, "doc-" + id, 0.5));
      byId.put(id, scores[i]);
    }
    return RetrievalState.initial()
        .withCandidateLists(
            List.of(new CandidateList(RetrievalListLabel.FUSED_LIST_LABEL, documents)))
        .withSelectionScores(byId);
  }

  private static StageOutcome cut(RelevanceCutoffProperties properties, RetrievalState state) {
    return new RelevanceCutoffStage(properties)
        .apply(context(Set.of(LIBRARY_ID), PROPERTIES), state);
  }

  private static List<String> ids(StageOutcome outcome) {
    return outcome.state().selection().stream().map(Document::getId).toList();
  }

  @Test
  void theFixedModeDoesNotRunAndLeavesTheSelectionAlone() {
    RetrievalState state = selectionWithScores(1.0, 0.1);

    StageOutcome outcome = cut(RelevanceCutoffProperties.DEFAULT, state);

    assertThat(outcome.state()).isSameAs(state);
    assertThat(outcome.explanation().status()).isEqualTo(StageStatus.DISABLED);
    assertThat(outcome.explanation().notes()).singleElement().asString().contains("top-k 8");
  }

  @Test
  void theRelativeModeKeepsWhatReachesTheShareOfTheBest() {
    StageOutcome outcome =
        cut(
            new RelevanceCutoffProperties(Mode.RELATIVE, 2, 0.6, 0.25),
            selectionWithScores(1.0, 0.9, 0.7, 0.5, 0.4));

    assertThat(ids(outcome)).containsExactly("a", "b", "c");
    assertThat(outcome.explanation().verdicts())
        .filteredOn(verdict -> verdict.outcome() == CandidateOutcome.DROPPED)
        .extracting(CandidateVerdict::chunkId, CandidateVerdict::reason)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("d", VerdictReason.BELOW_RELEVANCE_CUTOFF),
            org.assertj.core.groups.Tuple.tuple("e", VerdictReason.BELOW_RELEVANCE_CUTOFF));
    assertThat(outcome.explanation().notes())
        .singleElement()
        .asString()
        .startsWith("relative cutoff: kept 3 of 5 chunk(s) scoring at least 60 %");
  }

  @Test
  void theMinimumHoldsAgainstTheThreshold() {
    StageOutcome outcome =
        cut(
            new RelevanceCutoffProperties(Mode.RELATIVE, 4, 0.6, 0.25),
            selectionWithScores(1.0, 0.9, 0.7, 0.5, 0.4));

    assertThat(ids(outcome)).containsExactly("a", "b", "c", "d");
  }

  @Test
  void theKneeModeCutsBeforeTheFirstLargeDrop() {
    StageOutcome outcome =
        cut(
            new RelevanceCutoffProperties(Mode.KNEE, 2, 0.6, 0.25),
            selectionWithScores(1.0, 0.95, 0.9, 0.5, 0.45));

    assertThat(ids(outcome)).containsExactly("a", "b", "c");
    assertThat(outcome.explanation().notes())
        .singleElement()
        .asString()
        .isEqualTo(
            "knee cutoff: cut after 3 of 5 chunk(s), where the score fell by at least 25 % of the"
                + " best");
  }

  @Test
  void withoutALargeDropTheKneeModeKeepsEverything() {
    StageOutcome outcome =
        cut(
            new RelevanceCutoffProperties(Mode.KNEE, 2, 0.6, 0.25),
            selectionWithScores(1.0, 0.9, 0.8, 0.7, 0.6));

    assertThat(ids(outcome)).containsExactly("a", "b", "c", "d", "e");
    assertThat(outcome.explanation().notes())
        .singleElement()
        .asString()
        .startsWith("knee cutoff: no drop");
  }

  @Test
  void withoutAPositiveBestScoreNothingIsCut() {
    StageOutcome outcome =
        cut(
            new RelevanceCutoffProperties(Mode.RELATIVE, 1, 0.6, 0.25),
            selectionWithScores(-1.0, -2.0, -3.0));

    assertThat(ids(outcome)).containsExactly("a", "b", "c");
    assertThat(outcome.explanation().notes())
        .singleElement()
        .asString()
        .startsWith("relevance cutoff skipped");
  }

  /**
   * The sub-query quota fills round-robin, so the selection is not sorted by score. The bound comes
   * from the sorted scores and the order of what stays is kept.
   */
  @Test
  void aSelectionNotSortedByScoreIsCutByValueAndKeepsItsOrder() {
    RetrievalState roundRobin = selectionWithScores(1.0, 0.3, 0.9, 0.2, 0.8);

    StageOutcome relative =
        cut(new RelevanceCutoffProperties(Mode.RELATIVE, 2, 0.6, 0.25), roundRobin);
    StageOutcome knee = cut(new RelevanceCutoffProperties(Mode.KNEE, 2, 0.6, 0.25), roundRobin);

    assertThat(ids(relative)).containsExactly("a", "c", "e");
    assertThat(ids(knee)).containsExactly("a", "c", "e");
  }

  @Test
  void fewerChunksThanTheMinimumAreAllKept() {
    StageOutcome outcome =
        cut(
            new RelevanceCutoffProperties(Mode.RELATIVE, 10, 0.6, 0.25),
            selectionWithScores(1.0, 0.1, 0.05));

    assertThat(ids(outcome)).containsExactly("a", "b", "c");
    assertThat(outcome.explanation().notes())
        .singleElement()
        .asString()
        .isEqualTo("relevance cutoff: 3 chunk(s) do not exceed the minimum of 10, all kept");
  }

  /**
   * The cut sets the size of the context: document completion fills only up to what the cut left
   * and never brings a removed chunk back, though without the cut it would add both siblings.
   */
  @Test
  void documentCompletionStaysWithinTheCutAndNeverRestoresARemovedChunk() {
    Document a1 = chunk("a1", "doc-a", 0.9);
    Document b1 = chunk("b1", "doc-b", 0.8);
    Document a2 = chunk("a2", "doc-a", 0.7);
    Document b2 = chunk("b2", "doc-b", 0.6);
    RetrievalState searched =
        RetrievalState.initial()
            .withSearchResults(
                List.of(new CandidateList("vector search · sub-query 1", List.of(a1, b1, a2, b2))))
            .withCandidateLists(
                List.of(new CandidateList(RetrievalListLabel.FUSED_LIST_LABEL, List.of(a1, b1))));
    QueryProperties completing = new QueryProperties(8, 25, 1.0, 0.3, false, 3, 2, true, 50, 20, 2);

    List<String> withoutCut =
        new DocumentCompletionStage()
            .apply(context(Set.of(LIBRARY_ID), completing), searched).state().selection().stream()
                .map(Document::getId)
                .toList();
    List<String> afterCut =
        new DocumentCompletionStage()
                .apply(
                    context(Set.of(LIBRARY_ID), completing),
                    searched.withRelevanceCut(
                        new io.opaa.retrieval.RelevanceCut(2, Set.of("a2", "b2"))))
                .state()
                .selection()
                .stream()
                .map(Document::getId)
                .toList();

    assertThat(withoutCut).containsExactlyInAnyOrder("a1", "b1", "a2", "b2");
    assertThat(afterCut).containsExactly("a1", "b1");
  }

  /** With reranking the cut works on the rerank scores, not on the fused ones. */
  @Test
  void afterRerankingTheCutUsesTheRerankScores() {
    VectorChunkSearch vectorChunkSearch = mock(VectorChunkSearch.class);
    RerankModelRole rerank = mock(RerankModelRole.class);
    when(vectorChunkSearch.similaritySearch(any(SearchRequest.class)))
        .thenReturn(
            List.of(
                chunk("a", "doc-a", 0.9),
                chunk("b", "doc-b", 0.8),
                chunk("c", "doc-c", 0.7),
                chunk("d", "doc-d", 0.6)));
    when(rerank.rerank(anyString(), any()))
        .thenReturn(
            List.of(
                new io.opaa.llm.RerankClient.ScoredCandidate(0, 0.9),
                new io.opaa.llm.RerankClient.ScoredCandidate(2, 0.85),
                new io.opaa.llm.RerankClient.ScoredCandidate(1, 0.2),
                new io.opaa.llm.RerankClient.ScoredCandidate(3, 0.1)));
    QueryProperties reranking = new QueryProperties(8, 25, 1.0, 0.3, false, 3, 1, false, 50, 20, 2);

    RetrievalPipelineResult result =
        RetrievalPipelineTestSupport.pipeline(
                vectorChunkSearch,
                mock(FullTextChunkSearch.class),
                mock(ChunkEmbeddingLookup.class),
                mock(QueryDecompositionService.class),
                rerank,
                RetrievalPipelineProperties.allStagesEnabled(),
                new RelevanceCutoffProperties(Mode.RELATIVE, 1, 0.6, 0.25))
            .run(
                new io.opaa.retrieval.RetrievalContext(
                    "Frage",
                    List.of(),
                    Set.of(LIBRARY_ID),
                    io.opaa.metadata.MetadataFilter.NONE,
                    reranking,
                    io.opaa.retrieval.RerankAvailability.USABLE));

    assertThat(result.chunks()).extracting(Document::getId).containsExactly("a", "c");
  }

  /**
   * Through the whole pipeline: the fusion hands its scores on, and under RRF a chunk only one
   * search path found scores about half of one both paths found - so a relative threshold of 0.6
   * keeps exactly the chunks both paths agreed on.
   */
  @Test
  void thePipelineCutsOnTheFusedScores() {
    VectorChunkSearch vectorChunkSearch = mock(VectorChunkSearch.class);
    FullTextChunkSearch fullTextChunkSearch = mock(FullTextChunkSearch.class);
    when(vectorChunkSearch.similaritySearch(any(SearchRequest.class)))
        .thenReturn(
            List.of(
                chunk("a", "doc-a", 0.9),
                chunk("b", "doc-b", 0.8),
                chunk("c", "doc-c", 0.7),
                chunk("d", "doc-d", 0.6)));
    when(fullTextChunkSearch.search(anyString(), any(), any(), any(), anyInt()))
        .thenReturn(
            new FullTextSearchResult(
                List.of(
                    chunk("a", "doc-a", 0.09),
                    chunk("b", "doc-b", 0.08),
                    chunk("e", "doc-e", 0.07),
                    chunk("f", "doc-f", 0.06)),
                List.of()));

    RetrievalPipelineResult result =
        RetrievalPipelineTestSupport.pipeline(
                vectorChunkSearch,
                fullTextChunkSearch,
                mock(ChunkEmbeddingLookup.class),
                mock(QueryDecompositionService.class),
                mock(RerankModelRole.class),
                RetrievalPipelineProperties.allStagesEnabled(),
                new RelevanceCutoffProperties(Mode.RELATIVE, 1, 0.6, 0.25))
            .run(context(Set.of(LIBRARY_ID), PROPERTIES));

    assertThat(result.chunks()).extracting(Document::getId).containsExactly("a", "b");
    StageExplanation cutoff =
        result.explanation().stages().stream()
            .filter(stage -> stage.stage() == RetrievalStageName.RELEVANCE_CUTOFF)
            .findFirst()
            .orElseThrow();
    assertThat(cutoff.status()).isEqualTo(StageStatus.EXECUTED);
    assertThat(cutoff.incomingCount()).isEqualTo(6);
    assertThat(cutoff.outgoingCount()).isEqualTo(2);
  }
}
