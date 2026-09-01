package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
      new QueryProperties(8, 25, 1.0, 0.3, 1.0, false, 3, 2);

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
            new SubQueryDecompositionStage(queryDecompositionService),
            new VectorSearchStage(vectorStore),
            new MmrSelectionStage(chunkEmbeddingLookup),
            new RankFusionStage(),
            new DocumentCompletionStage(),
            pipelineProperties);
  }

  private RetrievalContext context(Set<UUID> searchScope) {
    return new RetrievalContext("Frage", List.of(), searchScope, PROPERTIES);
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
    assertThat(result.explanation().stages())
        .allSatisfy(stage -> assertThat(stage.status()).isEqualTo(StageStatus.EXECUTED));
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
    QueryProperties completing = new QueryProperties(3, 25, 1.0, 0.3, 1.0, false, 3, 2);
    QueryProperties notCompleting = new QueryProperties(3, 25, 1.0, 0.3, 1.0, false, 3, 1);
    RetrievalContext completingRun =
        new RetrievalContext("Frage", List.of(), Set.of(LIBRARY_ID), completing);
    RetrievalContext notCompletingRun =
        new RetrievalContext("Frage", List.of(), Set.of(LIBRARY_ID), notCompleting);

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
