package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;

/**
 * The lexical search stage's contract (#1048/#1049, docs/features/hybrid-retrieval.md, Arbeitspaket
 * 2 and 3): it searches only what the permission scope and the backfill gate allow, it records
 * everything it found in the explanation protocol, and it hands its lists on as further inputs of
 * the fusion.
 *
 * <p>The query itself is not mocked away here in the sense that matters: whether the permission
 * filter is part of the SQL rather than applied to its result is asserted against a real database
 * in {@code FullTextChunkSearchIntegrationTest}, exactly as the specification demands ("Der Filter
 * wird in einem Test abgesichert, der ihn tatsächlich ausführt").
 */
class FullTextSearchStageTest {

  private static final UUID COMPLETE_LIBRARY = UUID.randomUUID();
  private static final UUID BACKFILLING_LIBRARY = UUID.randomUUID();
  private static final QueryProperties PROPERTIES =
      new QueryProperties(8, 25, 1.0, 0.3, 1.0, false, 3, 2, true, 50);
  private static final QueryProperties LEXICAL_PATH_OFF =
      new QueryProperties(8, 25, 1.0, 0.3, 1.0, false, 3, 2, false, 50);

  private final FullTextChunkSearch search = mock(FullTextChunkSearch.class);
  private final FullTextBackfillGate gate = mock(FullTextBackfillGate.class);

  private FullTextSearchStage stage() {
    return new FullTextSearchStage(search, gate);
  }

  private static RetrievalContext context(Set<UUID> searchScope) {
    return context(searchScope, PROPERTIES);
  }

  private static RetrievalContext context(Set<UUID> searchScope, QueryProperties properties) {
    return new RetrievalContext("Was gilt nach § 35 BauGB?", List.of(), searchScope, properties);
  }

  private static RetrievalState scopedState(Set<UUID> searchScope) {
    return RetrievalState.initial()
        .withLibraryFilter(SearchScopeStage.libraryFilter(searchScope))
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
            () -> stage().apply(context(Set.of(COMPLETE_LIBRARY)), RetrievalState.initial()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ADR-0008");
    verifyNoInteractions(search, gate);
  }

  /**
   * The gate narrows the searched libraries to those whose backfill finished - and it narrows the
   * <em>permission</em> scope, never widens it: a library outside the scope can never reach the
   * query, because the gate is only ever asked about libraries the scope already contains.
   */
  @Test
  void searchesOnlyLibrariesWhoseBackfillIsComplete() {
    Set<UUID> scope = Set.of(COMPLETE_LIBRARY, BACKFILLING_LIBRARY);
    when(gate.searchableLibraries(scope)).thenReturn(Set.of(COMPLETE_LIBRARY));
    when(search.search(anyString(), any(), anyInt())).thenReturn(List.of(chunk("a")));

    StageOutcome outcome = stage().apply(context(scope), scopedState(scope));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Set<UUID>> libraries = ArgumentCaptor.forClass(Set.class);
    verify(search).search(anyString(), libraries.capture(), anyInt());
    assertThat(libraries.getValue()).containsExactly(COMPLETE_LIBRARY);
    assertThat(outcome.explanation().notes())
        .anySatisfy(note -> assertThat(note).contains("1 of 2 scoped libraries"));
  }

  /** No completed library means no query at all, and a protocol entry saying exactly why. */
  @Test
  void skipsTheQueryEntirelyWhileNoLibraryIsBackfilled() {
    Set<UUID> scope = Set.of(BACKFILLING_LIBRARY);
    when(gate.searchableLibraries(scope)).thenReturn(Set.of());

    StageOutcome outcome = stage().apply(context(scope), scopedState(scope));

    verifyNoInteractions(search);
    assertThat(outcome.explanation().verdicts()).isEmpty();
    assertThat(outcome.explanation().notes())
        .anySatisfy(note -> assertThat(note).contains("completed full-text backfill"));
  }

  @Test
  void switchedOffPropertySkipsTheQueryAndSaysSoInTheProtocol() {
    Set<UUID> scope = Set.of(COMPLETE_LIBRARY);

    StageOutcome outcome = stage().apply(context(scope, LEXICAL_PATH_OFF), scopedState(scope));

    verifyNoInteractions(search, gate);
    assertThat(outcome.explanation().status()).isEqualTo(StageStatus.EXECUTED);
    assertThat(outcome.explanation().notes())
        .anySatisfy(note -> assertThat(note).contains("switched off"));
  }

  /** One list per search query, each capped at {@code fetch-k}, each labelled as its own path. */
  @Test
  void runsOneQueryPerSearchQueryAndRecordsEveryCandidate() {
    Set<UUID> scope = Set.of(COMPLETE_LIBRARY);
    when(gate.searchableLibraries(scope)).thenReturn(scope);
    when(search.search(anyString(), any(), anyInt()))
        .thenReturn(List.of(chunk("a"), chunk("b")))
        .thenReturn(List.of(chunk("c")));
    RetrievalState state =
        RetrievalState.initial()
            .withLibraryFilter(SearchScopeStage.libraryFilter(scope))
            .withSearchQueries(List.of("q1", "q2"));

    StageOutcome outcome = stage().apply(context(scope), state);

    verify(search).search("q1", scope, PROPERTIES.fetchK());
    verify(search).search("q2", scope, PROPERTIES.fetchK());
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
   * searched then, exactly as the vector path does it.
   */
  @Test
  void searchesTheBareQuestionWhenNoSearchQueriesWereBuilt() {
    Set<UUID> scope = Set.of(COMPLETE_LIBRARY);
    when(gate.searchableLibraries(scope)).thenReturn(scope);
    when(search.search(anyString(), any(), anyInt())).thenReturn(List.of());
    RetrievalState state =
        RetrievalState.initial().withLibraryFilter(SearchScopeStage.libraryFilter(scope));

    stage().apply(context(scope), state);

    verify(search).search("Was gilt nach § 35 BauGB?", scope, PROPERTIES.fetchK());
  }

  /**
   * #1049: the stage's lists are handed on next to the vector path's, and its candidates extend the
   * run's pool - the two properties that make them an input of the fusion and a source for document
   * completion. It adds; it never touches what was already in flight.
   */
  @Test
  void handsItsListsOnNextToTheVectorPathsAndExtendsThePool() {
    Set<UUID> scope = Set.of(COMPLETE_LIBRARY);
    when(gate.searchableLibraries(scope)).thenReturn(scope);
    when(search.search(anyString(), any(), anyInt())).thenReturn(List.of(chunk("lexical-only")));
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
    Set<UUID> scope = Set.of(COMPLETE_LIBRARY);
    RetrievalState before =
        scopedState(scope)
            .withSearchResults(
                List.of(
                    new CandidateList(VectorSearchStage.listLabel(0), List.of(chunk("vector")))));

    StageOutcome outcome = stage().apply(context(scope, LEXICAL_PATH_OFF), before);

    assertThat(outcome.state()).isSameAs(before);
    verifyNoInteractions(search, gate);
  }

  /**
   * A failed list is left out of the fusion, the remaining ones are not: "Fällt der Volltextpfad
   * aus, läuft die Fusion mit den verbleibenden Listen weiter" (docs/features/hybrid-retrieval.md,
   * Arbeitspaket 3).
   */
  @Test
  void aFailedListIsOmittedWhileTheRemainingOnesStillReachTheFusion() {
    Set<UUID> scope = Set.of(COMPLETE_LIBRARY);
    when(gate.searchableLibraries(scope)).thenReturn(scope);
    when(search.search(anyString(), any(), anyInt()))
        .thenThrow(new IllegalStateException("relation chunk_full_text does not exist"))
        .thenReturn(List.of(chunk("second-list-hit")));
    RetrievalState state =
        RetrievalState.initial()
            .withLibraryFilter(SearchScopeStage.libraryFilter(scope))
            .withSearchQueries(List.of("q1", "q2"));

    StageOutcome outcome = stage().apply(context(scope), state);

    assertThat(outcome.state().candidateLists())
        .extracting(CandidateList::label)
        .containsExactly(FullTextSearchStage.listLabel(1));
    assertThat(outcome.state().candidatePool())
        .extracting(Document::getId)
        .containsExactly("second-list-hit");
    assertThat(outcome.explanation().notes())
        .anySatisfy(note -> assertThat(note).contains("lexical search failed"));
  }

  /**
   * A broken or missing full-text column costs search quality, never an error for the person asking
   * (docs/features/hybrid-retrieval.md, Arbeitspaket 3) - and the failure is stated in the protocol
   * rather than looking like "nothing matched".
   */
  @Test
  void aFailingQueryDegradesThePathInsteadOfFailingTheRun() {
    Set<UUID> scope = Set.of(COMPLETE_LIBRARY);
    when(gate.searchableLibraries(scope)).thenReturn(scope);
    when(search.search(anyString(), any(), anyInt()))
        .thenThrow(new IllegalStateException("relation chunk_full_text does not exist"));

    StageOutcome outcome = stage().apply(context(scope), scopedState(scope));

    assertThat(outcome.explanation().status()).isEqualTo(StageStatus.EXECUTED);
    assertThat(outcome.explanation().verdicts()).isEmpty();
    assertThat(outcome.explanation().notes())
        .anySatisfy(note -> assertThat(note).contains("lexical search failed"));
  }
}
