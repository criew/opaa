package io.opaa.query;

import io.opaa.indexing.VectorChunkStore;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

/**
 * Step 1 of docs/features/retrieval-algorithm.md as a pipeline stage: turns the search scope the
 * caller resolved into the {@code library_id IN (...)} filter every search stage passes straight
 * into {@link VectorStore#similaritySearch} - never a filter applied to a search result afterwards
 * (see {@code QueryService#query}'s Javadoc and
 * docs/features/spaces-and-assets.md#durchsetzung-zur-abfragezeit).
 *
 * <p>Resolves no permissions of its own: which libraries the acting user may read is decided before
 * the pipeline starts, and this stage only carries that decision into every search. It is
 * consequently the one stage that cannot be switched off ({@link #switchable()}).
 *
 * <p>An empty scope halts the run: no search, no LLM call for decomposition, no embedding lookup -
 * the same short-circuit {@code QueryService#query} took before this pipeline existed, and the
 * reason the remaining stages appear in the protocol as {@link StageStatus#NOT_REACHED} rather than
 * being quietly absent.
 */
@Component
class SearchScopeStage implements RetrievalStage {

  @Override
  public RetrievalStageName name() {
    return RetrievalStageName.SEARCH_SCOPE;
  }

  @Override
  public boolean switchable() {
    return false;
  }

  @Override
  public StageOutcome apply(RetrievalContext context, RetrievalState state) {
    Set<UUID> searchScope = context.searchScope();
    if (searchScope.isEmpty()) {
      return new StageOutcome(
          state.haltRun(),
          StageExplanation.executed(
              name(),
              0,
              0,
              List.of(),
              List.of("empty search scope: nothing readable in scope, retrieval halted")));
    }
    return new StageOutcome(
        state.withLibraryFilter(libraryFilter(searchScope)),
        StageExplanation.executed(
            name(),
            0,
            0,
            List.of(),
            List.of(
                "search scope: "
                    + searchScope.size()
                    + (searchScope.size() == 1 ? " library" : " libraries"),
                "permission filter applied inside every search of this run, never afterwards")));
  }

  /**
   * Builds the filter expression. Static and package-private so the invariant "the filter is built
   * exactly once, from the scope the run was started with" can be asserted directly in a test.
   */
  static Filter.Expression libraryFilter(Set<UUID> searchScope) {
    List<Object> libraryIdValues =
        searchScope.stream().map(UUID::toString).map(Object.class::cast).toList();
    return new FilterExpressionBuilder()
        .in(VectorChunkStore.LIBRARY_ID_METADATA_KEY, libraryIdValues)
        .build();
  }

  /**
   * The filter a search stage must apply, or an {@link IllegalStateException} - a search without a
   * permission filter is not a degraded mode this pipeline has.
   */
  static Filter.Expression requiredLibraryFilter(RetrievalState state) {
    Filter.Expression filter = state.libraryFilter();
    if (filter == null) {
      throw new IllegalStateException(
          "no permission filter in the pipeline state: a search stage ran before "
              + RetrievalStageName.SEARCH_SCOPE
              + ", which would search without a rights filter (ADR-0008 §5)");
    }
    return filter;
  }
}
