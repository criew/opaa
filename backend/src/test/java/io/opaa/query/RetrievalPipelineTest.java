package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.indexing.metadata.DocumentTypeVocabularyRepository;
import io.opaa.indexing.metadata.MetadataFilter;
import io.opaa.llm.RerankModelRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * <p>What this class deliberately does <b>not</b> test is the selection itself - that is {@link
 * RetrievalPipelineParityTest}'s job, which pins it against the pre-refactoring algorithm.
 */
class RetrievalPipelineTest {

  private static final UUID LIBRARY_ID = UUID.randomUUID();
  private static final QueryProperties PROPERTIES =
      new QueryProperties(8, 25, 1.0, 0.3, 1.0, false, 3, 2, false, 50);

  private final VectorStore vectorStore = mock(VectorStore.class);
  private final ChunkEmbeddingLookup chunkEmbeddingLookup = mock(ChunkEmbeddingLookup.class);
  private final QueryDecompositionService queryDecompositionService =
      mock(QueryDecompositionService.class);

  private static Document chunk(String id, String documentId, double score) {
    return Document.builder()
        .id(id)
        .text(id)
        .metadata(Map.of("document_id", documentId, "file_name", documentId + ".md"))
        .score(score)
        .build();
  }

  private RetrievalPipeline pipeline(RetrievalPipelineProperties pipelineProperties) {
    return new QueryConfiguration()
        .retrievalPipeline(
            new SearchScopeStage(),
            new MetadataFilterStage(mock(DocumentTypeVocabularyRepository.class)),
            new SubQueryDecompositionStage(queryDecompositionService),
            new VectorSearchStage(vectorStore),
            // The lexical path is switched off through every QueryProperties this class builds
            // (fullTextSearchEnabled = false): the structural guarantees asserted here are about
            // the vector path's candidates. The lexical stage's own behaviour is covered by
            // FullTextSearchStageTest.
            new FullTextSearchStage(
                mock(FullTextChunkSearch.class), mock(FullTextIndexCompleteness.class)),
            new MmrSelectionStage(chunkEmbeddingLookup),
            new RankFusionStage(),
            new RerankStage(mock(RerankModelRole.class)),
            new DocumentCompletionStage(),
            pipelineProperties);
  }

  private RetrievalContext context(Set<UUID> searchScope) {
    return new RetrievalContext(
        "Frage",
        List.of(),
        searchScope,
        MetadataFilter.NONE,
        PROPERTIES,
        RerankAvailability.SWITCHED_OFF);
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
   * "Abgeschaltet = Identität" for the MMR stage: without it, no per-list narrowing happens at all,
   * so the full {@code fetch-k} lists reach fusion, which then enforces the only remaining budget.
   * The switch must remove the stage, not neutralize it - a pipeline that still truncated per list
   * would measure the diversity term alone rather than the stage's contribution.
   */
  @Test
  void switchingOffTheMmrStageLeavesTheFullListsToFusion() {
    List<Document> candidates = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      candidates.add(chunk("chunk-" + i, "doc-" + i, 0.9 - i * 0.01));
    }
    stubSearch(List.copyOf(candidates));

    RetrievalPipelineResult withoutMmr =
        pipeline(new RetrievalPipelineProperties(Set.of(RetrievalStageName.MMR_SELECTION)))
            .run(context(Set.of(LIBRARY_ID)));

    // Fusion still caps at top-k, and the order is the search order: with one list, RRF ranks by
    // that list's own ranks.
    assertThat(withoutMmr.chunks())
        .extracting(Document::getId)
        .containsExactly(
            "chunk-0", "chunk-1", "chunk-2", "chunk-3", "chunk-4", "chunk-5", "chunk-6", "chunk-7");
    StageExplanation fusion =
        withoutMmr.explanation().stages().stream()
            .filter(stage -> stage.stage() == RetrievalStageName.RANK_FUSION)
            .findFirst()
            .orElseThrow();
    assertThat(fusion.incomingCount()).isEqualTo(12);
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
    QueryProperties twoChunkBudget =
        new QueryProperties(2, 25, 1.0, 0.3, 1.0, true, 3, 1, false, 50);

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
                    new QueryProperties(8, 25, 1.0, 0.3, 1.0, true, 3, 2, false, 50),
                    RerankAvailability.SWITCHED_OFF));

    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(captor.capture());
    assertThat(captor.getValue().getQuery()).isEqualTo("Zweite Frage");
    verifyNoInteractions(queryDecompositionService);
    assertThat(withoutDecomposition.searchQueries()).containsExactly("Zweite Frage");
  }

  /**
   * "Abgeschaltet = Identität": with document completion switched off, the pipeline returns exactly
   * what fusion selected - the same result {@code maxChunksPerDocument = 1} produces, since that is
   * what "this pipeline without that stage" means.
   */
  @Test
  void switchedOffStageIsTheIdentity() {
    List<Document> candidates =
        List.of(
            chunk("a-0", "doc-a", 0.9),
            chunk("b-0", "doc-b", 0.8),
            chunk("c-0", "doc-c", 0.7),
            chunk("a-1", "doc-a", 0.5));
    stubSearch(candidates);
    QueryProperties completing = new QueryProperties(3, 25, 1.0, 0.3, 1.0, false, 3, 2, false, 50);
    QueryProperties notCompleting =
        new QueryProperties(3, 25, 1.0, 0.3, 1.0, false, 3, 1, false, 50);
    RetrievalContext completingRun =
        new RetrievalContext(
            "Frage",
            List.of(),
            Set.of(LIBRARY_ID),
            MetadataFilter.NONE,
            completing,
            RerankAvailability.SWITCHED_OFF);
    RetrievalContext notCompletingRun =
        new RetrievalContext(
            "Frage",
            List.of(),
            Set.of(LIBRARY_ID),
            MetadataFilter.NONE,
            notCompleting,
            RerankAvailability.SWITCHED_OFF);

    List<Document> stageSwitchedOff =
        pipeline(new RetrievalPipelineProperties(Set.of(RetrievalStageName.DOCUMENT_COMPLETION)))
            .run(completingRun)
            .chunks();
    List<Document> stageNeutralizedByParameter =
        pipeline(RetrievalPipelineProperties.allStagesEnabled()).run(notCompletingRun).chunks();
    List<Document> stageActive =
        pipeline(RetrievalPipelineProperties.allStagesEnabled()).run(completingRun).chunks();

    assertThat(stageSwitchedOff).containsExactlyElementsOf(stageNeutralizedByParameter);
    // The scenario is one where the stage genuinely does something - otherwise the assertion above
    // would hold for a switch that never took effect.
    assertThat(stageActive).isNotEqualTo(stageSwitchedOff);
    assertThat(stageActive).extracting(Document::getId).contains("a-1");
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

  /**
   * A search stage without the filter stage before it must fail loudly, never search unfiltered.
   */
  @Test
  void searchStageRefusesToRunWithoutAPermissionFilter() {
    assertThatThrownBy(
            () ->
                new VectorSearchStage(vectorStore)
                    .apply(context(Set.of(LIBRARY_ID)), RetrievalState.initial()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ADR-0008");
    verifyNoInteractions(vectorStore);
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

  /**
   * The protocol answers the diagnosis question the specification is written for: was the document
   * never found, or found and displaced? A chunk that lost the per-list budget carries exactly that
   * verdict, on the stage that made the decision.
   */
  @Test
  void aDisplacedCandidateCarriesTheStageAndReasonThatDisplacedIt() {
    List<Document> candidates = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      candidates.add(chunk("chunk-" + i, "doc-" + i, 0.9 - i * 0.01));
    }
    stubSearch(List.copyOf(candidates));

    RetrievalPipelineResult result =
        pipeline(RetrievalPipelineProperties.allStagesEnabled()).run(context(Set.of(LIBRARY_ID)));

    assertThat(result.chunks()).hasSize(PROPERTIES.topK());
    List<StageExplanation> droppedIn = result.explanation().stagesThatDropped("chunk-11");
    assertThat(droppedIn)
        .extracting(StageExplanation::stage)
        .contains(RetrievalStageName.MMR_SELECTION);
    assertThat(result.explanation().forChunk("chunk-11"))
        .anySatisfy(
            verdict -> {
              assertThat(verdict.outcome()).isEqualTo(CandidateOutcome.DROPPED);
              assertThat(verdict.reason()).isEqualTo(VerdictReason.OUTSIDE_LIST_BUDGET);
              assertThat(verdict.value()).isNotNull();
            });
  }
}
