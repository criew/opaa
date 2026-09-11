package io.opaa.query.retrieval.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.indexing.metadata.MetadataFilter;
import io.opaa.query.QueryProperties;
import io.opaa.query.retrieval.CandidateList;
import io.opaa.query.retrieval.CandidateVerdict;
import io.opaa.query.retrieval.RerankAvailability;
import io.opaa.query.retrieval.RetrievalContext;
import io.opaa.query.retrieval.RetrievalState;
import io.opaa.query.retrieval.StageOutcome;
import io.opaa.query.retrieval.StageStatus;
import io.opaa.query.retrieval.VerdictReason;
import io.opaa.query.retrieval.scope.SearchScopeStage;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.Filter;

/**
 * The lexical search stage's contract (#1048/#1049, docs/features/hybrid-retrieval.md, Arbeitspaket
 * 2 and 3): it searches only what the permission scope allows, it records everything it found in
 * the explanation protocol, and it hands its lists on as further inputs of the fusion.
 *
 * <p>The query itself is not mocked away here in the sense that matters: whether the permission
 * filter is part of the SQL rather than applied to its result is asserted against a real database
 * in {@code FullTextChunkSearchIntegrationTest}, exactly as the specification demands ("Der Filter
 * wird in einem Test abgesichert, der ihn tatsächlich ausführt").
 */
class FullTextSearchStageTest {

  private static final UUID SCOPED_LIBRARY = UUID.randomUUID();
  private static final UUID SECOND_LIBRARY = UUID.randomUUID();
  private static final QueryProperties PROPERTIES =
      new QueryProperties(8, 25, 1.0, 0.3, false, 3, 2, true, 50, 20, 2);
  private static final QueryProperties LEXICAL_PATH_OFF =
      new QueryProperties(8, 25, 1.0, 0.3, false, 3, 2, false, 50, 20, 2);

  private final FullTextChunkSearch search = mock(FullTextChunkSearch.class);

  private FullTextSearchStage stage() {
    return new FullTextSearchStage(search);
  }

  private static RetrievalContext context(Set<UUID> searchScope) {
    return context(searchScope, PROPERTIES);
  }

  private static RetrievalContext context(Set<UUID> searchScope, QueryProperties properties) {
    return new RetrievalContext(
        "Was gilt nach § 35 BauGB?",
        List.of(),
        searchScope,
        MetadataFilter.NONE,
        properties,
        RerankAvailability.SWITCHED_OFF);
  }

  /** The permission filter exactly as {@link SearchScopeStage} establishes it for the run. */
  private static Filter.Expression permissionFilter(Set<UUID> searchScope) {
    return new SearchScopeStage()
        .apply(context(searchScope), RetrievalState.initial())
        .state()
        .libraryFilter();
  }

  private static RetrievalState scopedState(Set<UUID> searchScope) {
    return RetrievalState.initial()
        .withLibraryFilter(permissionFilter(searchScope))
        .withSearchQueries(List.of("Außenbereich § 35"));
  }

  private static Document chunk(String id) {
    return Document.builder()
        .id(id)
        .text(id)
        .metadata(Map.of("document_id", "doc-" + id, "file_name", id + ".md"))
        .score(0.4)
        .build();
  }

  /** ADR-0008 §5: no search stage runs before the stage that establishes the filter. */
  @Test
  void refusesToRunWithoutAPermissionFilter() {
    assertThatThrownBy(
            () -> stage().apply(context(Set.of(SCOPED_LIBRARY)), RetrievalState.initial()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ADR-0008");
    verifyNoInteractions(search);
  }

  /**
   * Exactly the permission scope reaches the query - no library beyond it (ADR-0008 §5) and none of
   * it held back either: the full-text row is written with the vector row, so there is no scoped
   * library the lexical path has to leave out.
   */
  @Test
  void searchesExactlyThePermissionScope() {
    Set<UUID> scope = Set.of(SCOPED_LIBRARY, SECOND_LIBRARY);
    when(search.search(anyString(), any(), any(), any(), anyInt())).thenReturn(List.of(chunk("a")));

    StageOutcome outcome = stage().apply(context(scope), scopedState(scope));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Set<UUID>> libraries = ArgumentCaptor.forClass(Set.class);
    verify(search).search(anyString(), libraries.capture(), any(), any(), anyInt());
    assertThat(libraries.getValue()).containsExactlyInAnyOrder(SCOPED_LIBRARY, SECOND_LIBRARY);
    assertThat(outcome.explanation().status()).isEqualTo(StageStatus.EXECUTED);
  }

  @Test
  void switchedOffPropertySkipsTheQueryAndSaysSoInTheProtocol() {
    Set<UUID> scope = Set.of(SCOPED_LIBRARY);

    StageOutcome outcome = stage().apply(context(scope, LEXICAL_PATH_OFF), scopedState(scope));

    verifyNoInteractions(search);
    assertThat(outcome.explanation().status()).isEqualTo(StageStatus.EXECUTED);
    assertThat(outcome.explanation().notes())
        .anySatisfy(note -> assertThat(note).contains("switched off"));
  }

  /** One list per search query, each capped at {@code fetch-k}, each labelled as its own path. */
  @Test
  void runsOneQueryPerSearchQueryAndRecordsEveryCandidate() {
    Set<UUID> scope = Set.of(SCOPED_LIBRARY);
    when(search.search(anyString(), any(), any(), any(), anyInt()))
        .thenReturn(List.of(chunk("a"), chunk("b")))
        .thenReturn(List.of(chunk("c")));
    RetrievalState state =
        RetrievalState.initial()
            .withLibraryFilter(permissionFilter(scope))
            .withSearchQueries(List.of("q1", "q2"));

    StageOutcome outcome = stage().apply(context(scope), state);

    verify(search).search("q1", scope, MetadataFilter.NONE, List.of(), PROPERTIES.fetchK());
    verify(search).search("q2", scope, MetadataFilter.NONE, List.of(), PROPERTIES.fetchK());
    // Nothing was in flight, three candidates leave: the stage adds, it never narrows.
    assertThat(outcome.explanation().incomingCount()).isZero();
    assertThat(outcome.explanation().outgoingCount()).isEqualTo(3);
    assertThat(outcome.state().candidateLists())
        .extracting(CandidateList::label)
        .containsExactly(FullTextSearchStage.listLabel(0), FullTextSearchStage.listLabel(1));
    assertThat(outcome.explanation().verdicts())
        .extracting(CandidateVerdict::chunkId)
        .containsExactly("a", "b", "c");
    assertThat(outcome.explanation().verdicts())
        .extracting(CandidateVerdict::listLabel)
        .containsExactly(
            FullTextSearchStage.listLabel(0),
            FullTextSearchStage.listLabel(0),
            FullTextSearchStage.listLabel(1));
    assertThat(outcome.explanation().verdicts())
        .allSatisfy(
            verdict -> assertThat(verdict.reason()).isEqualTo(VerdictReason.RETRIEVED_BY_SEARCH));
  }

  /**
   * Without a decomposition stage the state carries no search queries; the bare question is
   * searched then, exactly as the vector path does it - and the derived query is written back, so a
   * run whose only search stage is this one never reports having searched nothing while it did.
   */
  @Test
  void searchesTheBareQuestionWhenNoSearchQueriesWereBuiltAndRecordsIt() {
    Set<UUID> scope = Set.of(SCOPED_LIBRARY);
    when(search.search(anyString(), any(), any(), any(), anyInt())).thenReturn(List.of());
    RetrievalState state = RetrievalState.initial().withLibraryFilter(permissionFilter(scope));

    StageOutcome outcome = stage().apply(context(scope), state);

    verify(search)
        .search(
            "Was gilt nach § 35 BauGB?",
            scope,
            MetadataFilter.NONE,
            List.of(),
            PROPERTIES.fetchK());
    assertThat(outcome.state().searchQueries()).containsExactly("Was gilt nach § 35 BauGB?");
  }

  /** Queries an earlier stage built are the ones searched and are not overwritten. */
  @Test
  void keepsTheSearchQueriesAnEarlierStageBuilt() {
    Set<UUID> scope = Set.of(SCOPED_LIBRARY);
    when(search.search(anyString(), any(), any(), any(), anyInt())).thenReturn(List.of());

    StageOutcome outcome = stage().apply(context(scope), scopedState(scope));

    assertThat(outcome.state().searchQueries()).containsExactly("Außenbereich § 35");
  }

  /**
   * #1049: the stage's lists are handed on next to the vector path's, and its candidates extend the
   * run's pool - the two properties that make them an input of the fusion and a source for document
   * completion. It adds; it never touches what was already in flight.
   */
  @Test
  void handsItsListsOnNextToTheVectorPathsAndExtendsThePool() {
    Set<UUID> scope = Set.of(SCOPED_LIBRARY);
    when(search.search(anyString(), any(), any(), any(), anyInt()))
        .thenReturn(List.of(chunk("lexical-only")));
    RetrievalState before =
        scopedState(scope)
            .withSearchResults(
                List.of(
                    new CandidateList(VectorSearchStage.listLabel(0), List.of(chunk("vector")))));

    StageOutcome outcome = stage().apply(context(scope), before);

    assertThat(outcome.state().candidateLists())
        .extracting(CandidateList::label)
        .containsExactly(VectorSearchStage.listLabel(0), FullTextSearchStage.listLabel(0));
    assertThat(outcome.state().candidatePool())
        .extracting(Document::getId)
        .containsExactly("vector", "lexical-only");
    // The vector path's list is unchanged - a search stage adds a list, it never edits one.
    assertThat(outcome.state().candidateLists().get(0).documents())
        .isEqualTo(before.candidateLists().get(0).documents());
  }

  /**
   * The switched-off path is the identity for the selection: a run with {@code
   * fullTextSearchEnabled = false} carries exactly the lists it carried before this stage - the
   * {@code vector-only} measurement variant, and the state the committed pre-#1049 baselines were
   * drawn in.
   */
  @Test
  void switchedOffPathLeavesTheStateUntouched() {
    Set<UUID> scope = Set.of(SCOPED_LIBRARY);
    RetrievalState before =
        scopedState(scope)
            .withSearchResults(
                List.of(
                    new CandidateList(VectorSearchStage.listLabel(0), List.of(chunk("vector")))));

    StageOutcome outcome = stage().apply(context(scope, LEXICAL_PATH_OFF), before);

    assertThat(outcome.state()).isSameAs(before);
    verifyNoInteractions(search);
  }

  /**
   * A failing query fails the run rather than quietly dropping half the hybrid search: a run that
   * silently loses the lexical list returns a worse answer while looking like a normal one.
   */
  @Test
  void aFailingQueryFailsTheRun() {
    Set<UUID> scope = Set.of(SCOPED_LIBRARY);
    when(search.search(anyString(), any(), any(), any(), anyInt()))
        .thenThrow(new IllegalStateException("column content_tsv does not exist"));

    assertThatThrownBy(() -> stage().apply(context(scope), scopedState(scope)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("content_tsv");
  }

  /** The same for a failure on a later sub-query: no partially filled result reaches the fusion. */
  @Test
  void aFailingQueryOfOneSubQueryFailsTheRunAsWell() {
    Set<UUID> scope = Set.of(SCOPED_LIBRARY);
    when(search.search(anyString(), any(), any(), any(), anyInt()))
        .thenReturn(List.of(chunk("first-list-hit")))
        .thenThrow(new IllegalStateException("column content_tsv does not exist"));
    RetrievalState state =
        RetrievalState.initial()
            .withLibraryFilter(permissionFilter(scope))
            .withSearchQueries(List.of("q1", "q2"));

    assertThatThrownBy(() -> stage().apply(context(scope), state))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("content_tsv");
  }
}
