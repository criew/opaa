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
 * The lexical search stage's contract (#1048, docs/features/hybrid-retrieval.md, Arbeitspaket 2):
 * it searches only what the permission scope and the backfill gate allow, it records everything it
 * found in the explanation protocol, and - until #1049 - it hands the state on untouched.
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
      new QueryProperties(8, 25, 1.0, 0.3, 1.0, false, 3, 2);

  private final FullTextChunkSearch search = mock(FullTextChunkSearch.class);
  private final FullTextBackfillGate gate = mock(FullTextBackfillGate.class);

  private FullTextSearchStage stage(boolean enabled) {
    return new FullTextSearchStage(search, gate, new FullTextSearchProperties(enabled));
  }

  private static RetrievalContext context(Set<UUID> searchScope) {
    return new RetrievalContext("Was gilt nach § 35 BauGB?", List.of(), searchScope, PROPERTIES);
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
            () -> stage(true).apply(context(Set.of(COMPLETE_LIBRARY)), RetrievalState.initial()))
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

    StageOutcome outcome = stage(true).apply(context(scope), scopedState(scope));

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

    StageOutcome outcome = stage(true).apply(context(scope), scopedState(scope));

    verifyNoInteractions(search);
    assertThat(outcome.explanation().verdicts()).isEmpty();
    assertThat(outcome.explanation().notes())
        .anySatisfy(note -> assertThat(note).contains("completed full-text backfill"));
  }

  @Test
  void switchedOffPropertySkipsTheQueryAndSaysSoInTheProtocol() {
    Set<UUID> scope = Set.of(COMPLETE_LIBRARY);

    StageOutcome outcome = stage(false).apply(context(scope), scopedState(scope));

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

    StageOutcome outcome = stage(true).apply(context(scope), state);

    verify(search).search("q1", scope, PROPERTIES.fetchK());
    verify(search).search("q2", scope, PROPERTIES.fetchK());
    // Incoming equals outgoing: the stage passes the lists in flight on unchanged. Its own three
    // candidates are in the verdicts and in a note, not in the counts - they are not handed on
    // until #1049 makes them an input of the fusion.
    assertThat(outcome.explanation().incomingCount())
        .isEqualTo(outcome.explanation().outgoingCount());
    assertThat(outcome.explanation().notes())
        .anySatisfy(note -> assertThat(note).contains("3 lexical candidate(s) found"));
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

    stage(true).apply(context(scope), state);

    verify(search).search("Was gilt nach § 35 BauGB?", scope, PROPERTIES.fetchK());
  }

  /**
   * Until #1049 the stage is the identity for everything downstream: same lists in flight, same
   * candidate pool, same selection. That is what keeps the committed benchmark baselines valid
   * while the path is built.
   */
  @Test
  void leavesTheStateUntouchedSoTheSelectionCannotMove() {
    Set<UUID> scope = Set.of(COMPLETE_LIBRARY);
    when(gate.searchableLibraries(scope)).thenReturn(scope);
    when(search.search(anyString(), any(), anyInt())).thenReturn(List.of(chunk("lexical-only")));
    RetrievalState before =
        scopedState(scope)
            .withSearchResults(List.of(new CandidateList("vector", List.of(chunk("vector-hit")))));

    StageOutcome outcome = stage(true).apply(context(scope), before);

    assertThat(outcome.state()).isSameAs(before);
    assertThat(outcome.state().selection())
        .extracting(Document::getId)
        .containsExactly("vector-hit");
    assertThat(outcome.state().candidatePool())
        .extracting(Document::getId)
        .containsExactly("vector-hit");
    // Found and recorded all the same - the protocol is where the path is visible before it acts.
    assertThat(outcome.explanation().verdicts())
        .extracting(CandidateVerdict::chunkId)
        .containsExactly("lexical-only");
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

    StageOutcome outcome = stage(true).apply(context(scope), scopedState(scope));

    assertThat(outcome.explanation().status()).isEqualTo(StageStatus.EXECUTED);
    assertThat(outcome.explanation().verdicts()).isEmpty();
    assertThat(outcome.explanation().notes())
        .anySatisfy(note -> assertThat(note).contains("lexical search failed"));
  }
}
