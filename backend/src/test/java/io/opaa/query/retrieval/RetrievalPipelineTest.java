package io.opaa.query.retrieval;

import static io.opaa.query.retrieval.RetrievalPipelineTestSupport.chunk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.indexing.metadata.MetadataFilter;
import io.opaa.llm.RerankModelRole;
import io.opaa.query.QueryProperties;
import io.opaa.query.retrieval.ranking.ChunkEmbeddingLookup;
import io.opaa.query.retrieval.scope.SearchScopeStage;
import io.opaa.query.retrieval.search.FullTextChunkSearch;
import io.opaa.query.retrieval.search.QueryDecompositionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * The structural guarantees of the staged pipeline (issue #1046, docs/features/hybrid-retrieval.md,
 * Arbeitspaket 1): every registered stage appears in the explanation protocol, a switched-off stage
 * is the identity, and no stage can widen the permission scope or the candidate pool.
 *
 * <p>What this class deliberately does <b>not</b> test is the selection itself - that is {@code
 * RetrievalPipelineParityTest}'s job, which pins it against the pre-refactoring algorithm - nor the
 * behaviour of a single stage, which the {@code *StageTest} classes of the stage packages cover.
 */
class RetrievalPipelineTest {

  private static final UUID LIBRARY_ID = UUID.randomUUID();
  private static final QueryProperties PROPERTIES =
      new QueryProperties(8, 25, 1.0, 0.3, false, 3, 2, false, 50);

  private final VectorStore vectorStore = mock(VectorStore.class);
  private final ChunkEmbeddingLookup chunkEmbeddingLookup = mock(ChunkEmbeddingLookup.class);
  private final QueryDecompositionService queryDecompositionService =
      mock(QueryDecompositionService.class);

  /**
   * The lexical path is switched off through every QueryProperties this class builds
   * (fullTextSearchEnabled = false): the structural guarantees asserted here are about the vector
   * path's candidates. The lexical stage's own behaviour is covered by FullTextSearchStageTest.
   */
  private RetrievalPipeline pipeline(RetrievalPipelineProperties pipelineProperties) {
    return RetrievalPipelineTestSupport.pipeline(
        vectorStore,
        mock(FullTextChunkSearch.class),
        chunkEmbeddingLookup,
        queryDecompositionService,
        mock(RerankModelRole.class),
        pipelineProperties);
  }

  private RetrievalContext context(Set<UUID> searchScope) {
    return RetrievalPipelineTestSupport.context(searchScope, PROPERTIES);
  }

  private void stubSearch(List<Document> results) {
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(results);
  }

  /**
   * The acceptance criterion of issue #1046: as many protocol entries as registered stages. A stage
   * added later and forgotten in the protocol would make a candidate vanish without a trace in a
   * diagnosis that looks complete.
   */
  @Test
  void protocolHasOneEntryPerRegisteredStage() {
    stubSearch(List.of(chunk("a-0", "doc-a", 0.9)));
    RetrievalPipeline pipeline = pipeline(RetrievalPipelineProperties.allStagesEnabled());

    RetrievalPipelineResult result = pipeline.run(context(Set.of(LIBRARY_ID)));

    assertThat(result.explanation().stages()).hasSameSizeAs(pipeline.registeredStages());
    assertThat(result.explanation().stages())
        .extracting(StageExplanation::stage)
        .containsExactlyElementsOf(pipeline.registeredStages());
    // Every stage but the reranker runs: this pipeline is wired with a rerank model role that is
    // not usable, the shipped configuration (OPAA_RERANK_ENABLED off, #1050). A switched-off stage
    // is recorded, not omitted - that is the property this test exists for.
    assertThat(result.explanation().stages())
        .filteredOn(stage -> stage.stage() != RetrievalStageName.RERANK)
        .allSatisfy(stage -> assertThat(stage.status()).isEqualTo(StageStatus.EXECUTED));
    assertThat(result.explanation().stages())
        .filteredOn(stage -> stage.stage() == RetrievalStageName.RERANK)
        .singleElement()
        .satisfies(stage -> assertThat(stage.status()).isEqualTo(StageStatus.DISABLED));
  }

  /** The count holds for a run with a switched-off stage too - it is recorded, not skipped. */
  @Test
  void switchedOffStageStillAppearsInTheProtocol() {
    stubSearch(List.of(chunk("a-0", "doc-a", 0.9), chunk("a-1", "doc-a", 0.8)));
    RetrievalPipeline pipeline =
        pipeline(new RetrievalPipelineProperties(Set.of(RetrievalStageName.DOCUMENT_COMPLETION)));

    RetrievalPipelineResult result = pipeline.run(context(Set.of(LIBRARY_ID)));

    assertThat(result.explanation().stages()).hasSameSizeAs(pipeline.registeredStages());
    StageExplanation completion =
        result.explanation().stages().stream()
            .filter(stage -> stage.stage() == RetrievalStageName.DOCUMENT_COMPLETION)
            .findFirst()
            .orElseThrow();
    assertThat(completion.status()).isEqualTo(StageStatus.DISABLED);
    assertThat(completion.incomingCount()).isEqualTo(completion.outgoingCount());
  }

  /**
   * Without the fusion stage the lists are not merged by rank at all: {@link
   * RetrievalState#selection()} collapses them by ordered concatenation deduplicated by chunk id,
   * and fusion's {@code top-k} cap consequently does not apply. That is what "this pipeline without
   * that stage" means here - it is deliberately not a second, quieter fusion rule.
   */
  @Test
  void switchingOffTheFusionStageCollapsesTheListsByConcatenationWithoutTheBudget() {
    Document shared = chunk("shared", "doc-shared", 0.9);
    Document firstOnly = chunk("first", "doc-first", 0.8);
    Document secondOnly = chunk("second", "doc-second", 0.7);
    when(queryDecompositionService.decompose(any(), any(), any(Integer.class)))
        .thenReturn(List.of("q1", "q2"));
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenAnswer(
            invocation -> {
              SearchRequest request = invocation.getArgument(0);
              return "q1".equals(request.getQuery())
                  ? List.of(shared, firstOnly)
                  : List.of(shared, secondOnly);
            });
    QueryProperties twoChunkBudget = new QueryProperties(2, 25, 1.0, 0.3, true, 3, 1, false, 50);

    RetrievalPipelineResult withoutFusion =
        pipeline(new RetrievalPipelineProperties(Set.of(RetrievalStageName.RANK_FUSION)))
            .run(
                new RetrievalContext(
                    "Frage",
                    List.of(),
                    Set.of(LIBRARY_ID),
                    MetadataFilter.NONE,
                    twoChunkBudget,
                    RerankAvailability.SWITCHED_OFF));

    // Deduplicated by chunk id (shared appears once, at its first position), in list order, and
    // three chunks despite a top-k of two - the cap belonged to the stage that is gone.
    assertThat(withoutFusion.chunks())
        .extracting(Document::getId)
        .containsExactly("shared", "first", "second");
  }

  /**
   * Without the decomposition stage there is no query-building step: the bare question is searched,
   * without the conversation-history prefix the stage's own fallback prepends - and the run still
   * reports the query it actually searched.
   */
  @Test
  void switchingOffTheDecompositionStageSearchesTheBareQuestion() {
    stubSearch(List.of(chunk("a-0", "doc-a", 0.9)));
    List<Message> history = List.of(new UserMessage("Erste Frage"));

    RetrievalPipelineResult withoutDecomposition =
        pipeline(
                new RetrievalPipelineProperties(Set.of(RetrievalStageName.SUB_QUERY_DECOMPOSITION)))
            .run(
                new RetrievalContext(
                    "Zweite Frage",
                    history,
                    Set.of(LIBRARY_ID),
                    MetadataFilter.NONE,
                    new QueryProperties(8, 25, 1.0, 0.3, true, 3, 2, false, 50),
                    RerankAvailability.SWITCHED_OFF));

    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(captor.capture());
    assertThat(captor.getValue().getQuery()).isEqualTo("Zweite Frage");
    verifyNoInteractions(queryDecompositionService);
    assertThat(withoutDecomposition.searchQueries()).containsExactly("Zweite Frage");
  }

  /**
   * An empty scope halts the run before any search, LLM call or embedding lookup - and the stages
   * that never ran are still in the protocol, as {@link StageStatus#NOT_REACHED}.
   */
  @Test
  void emptyScopeHaltsTheRunAndRecordsTheRemainingStagesAsNotReached() {
    RetrievalPipeline pipeline = pipeline(RetrievalPipelineProperties.allStagesEnabled());

    RetrievalPipelineResult result = pipeline.run(context(Set.of()));

    assertThat(result.chunks()).isEmpty();
    assertThat(result.searchQueries()).isEmpty();
    assertThat(result.explanation().stages()).hasSameSizeAs(pipeline.registeredStages());
    assertThat(result.explanation().stages().subList(1, result.explanation().stages().size()))
        .allSatisfy(stage -> assertThat(stage.status()).isEqualTo(StageStatus.NOT_REACHED));
    verifyNoInteractions(vectorStore, chunkEmbeddingLookup, queryDecompositionService);
  }

  /**
   * ADR-0008 §5: the stage that establishes the permission filter is not a measurable variant, and
   * the refusal is a configuration error at startup rather than a surprise at query time.
   */
  @Test
  void permissionFilterStageCannotBeSwitchedOff() {
    assertThatThrownBy(
            () ->
                pipeline(new RetrievalPipelineProperties(Set.of(RetrievalStageName.SEARCH_SCOPE))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("permission");
  }

  /** Two stages of the same name would make every verdict ambiguous about which one issued it. */
  @Test
  void aStageCannotBeRegisteredTwice() {
    assertThatThrownBy(
            () ->
                new RetrievalPipeline(
                    List.of(new SearchScopeStage(), new SearchScopeStage()),
                    RetrievalPipelineProperties.allStagesEnabled()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("registered twice");
  }

  /** Naming a stage this pipeline does not have is a typo, not a silent no-op. */
  @Test
  void switchingOffAnUnregisteredStageIsRejected() {
    assertThatThrownBy(
            () ->
                new RetrievalPipeline(
                    List.of(new SearchScopeStage()),
                    new RetrievalPipelineProperties(Set.of(RetrievalStageName.RANK_FUSION))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a registered stage");
  }

  /**
   * No stage may see a candidate the searches did not return: every chunk any stage passed a
   * verdict on comes from the pool the search stage established.
   */
  @Test
  void noStageEverSeesACandidateOutsideTheSearchResults() {
    List<Document> candidates =
        List.of(chunk("a-0", "doc-a", 0.9), chunk("a-1", "doc-a", 0.5), chunk("b-0", "doc-b", 0.8));
    stubSearch(candidates);

    RetrievalPipelineResult result =
        pipeline(RetrievalPipelineProperties.allStagesEnabled()).run(context(Set.of(LIBRARY_ID)));

    List<String> retrievedIds = candidates.stream().map(Document::getId).toList();
    List<String> verdictIds = new ArrayList<>();
    result
        .explanation()
        .stages()
        .forEach(stage -> stage.verdicts().forEach(v -> verdictIds.add(v.chunkId())));
    assertThat(verdictIds).isSubsetOf(retrievedIds);
    assertThat(result.chunks()).extracting(Document::getId).isSubsetOf(retrievedIds);
  }
}
