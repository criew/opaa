package io.opaa.query;

import io.opaa.indexing.metadata.MetadataFilter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.Filter;

/**
 * What one stage hands the next: the search queries, the permission filter every search must apply,
 * the metadata filter a search AND-s to it, the candidate lists currently in flight, and the pool
 * of everything a search stage ever returned in this run.
 *
 * <p><b>{@code candidatePool} is the ceiling of the whole run.</b> Only a search stage may extend
 * it; every other stage draws from it and can therefore never see more candidates than it was
 * handed - the invariant that keeps document completion (#932) from reaching past the permission
 * filter the searches themselves applied.
 *
 * <p>Immutable: a stage returns a new state rather than mutating this one, so a stage cannot alter
 * what an earlier stage recorded in the explanation protocol.
 *
 * @param searchQueries the queries the search stages run, one each; empty before {@link
 *     RetrievalStageName#SUB_QUERY_DECOMPOSITION} ran or when that stage is switched off.
 * @param libraryFilter the {@code library_id IN (...)} filter {@link
 *     RetrievalStageName#SEARCH_SCOPE} built; {@code null} only before that stage ran.
 * @param metadataFilter the core-field filter {@link RetrievalStageName#METADATA_FILTER} carried
 *     into the run (#1070); {@link MetadataFilter#NONE} before that stage ran, when it is switched
 *     off, or when the caller set none. Never a widening of the permission filter: every search
 *     stage AND-s it to {@link #libraryFilter}.
 * @param metadataFilterExpression the vector-path form of {@link #metadataFilter}; {@code null}
 *     exactly when that filter is empty.
 * @param candidateLists the lists currently in flight - one per search query and search path until
 *     fusion collapses them to one.
 * @param candidatePool every candidate any search stage returned in this run, in the order the
 *     searches produced them.
 * @param halted {@code true} once a stage determined there is nothing left to do (an empty search
 *     scope); the remaining stages are then recorded as not run rather than executed.
 */
public record RetrievalState(
    List<String> searchQueries,
    Filter.Expression libraryFilter,
    MetadataFilter metadataFilter,
    Filter.Expression metadataFilterExpression,
    List<CandidateList> candidateLists,
    List<Document> candidatePool,
    boolean halted) {

  public RetrievalState {
    searchQueries = List.copyOf(searchQueries);
    metadataFilter = metadataFilter == null ? MetadataFilter.NONE : metadataFilter;
    candidateLists = List.copyOf(candidateLists);
    candidatePool = List.copyOf(candidatePool);
  }

  /** The state a run starts in: no queries, no filter, no candidates. */
  public static RetrievalState initial() {
    return new RetrievalState(
        List.of(), null, MetadataFilter.NONE, null, List.of(), List.of(), false);
  }

  public RetrievalState withSearchQueries(List<String> queries) {
    return new RetrievalState(
        queries,
        libraryFilter,
        metadataFilter,
        metadataFilterExpression,
        candidateLists,
        candidatePool,
        halted);
  }

  public RetrievalState withLibraryFilter(Filter.Expression filter) {
    return new RetrievalState(
        searchQueries,
        filter,
        metadataFilter,
        metadataFilterExpression,
        candidateLists,
        candidatePool,
        halted);
  }

  /**
   * Carries the metadata filter into the run, in both forms the two search paths need - what {@link
   * RetrievalStageName#METADATA_FILTER} does. The permission filter is untouched.
   */
  public RetrievalState withMetadataFilter(MetadataFilter filter, Filter.Expression expression) {
    return new RetrievalState(
        searchQueries, libraryFilter, filter, expression, candidateLists, candidatePool, halted);
  }

  /**
   * Replaces the lists in flight without touching {@link #candidatePool} - what every stage after
   * the search stages does.
   */
  public RetrievalState withCandidateLists(List<CandidateList> lists) {
    return new RetrievalState(
        searchQueries,
        libraryFilter,
        metadataFilter,
        metadataFilterExpression,
        lists,
        candidatePool,
        halted);
  }

  /**
   * Adds newly searched lists and extends the pool by exactly their contents - the only way the
   * pool ever grows, and therefore the only place a stage may introduce a candidate the run did not
   * hold before.
   */
  public RetrievalState withSearchResults(List<CandidateList> lists) {
    List<CandidateList> mergedLists = new ArrayList<>(candidateLists);
    mergedLists.addAll(lists);
    List<Document> extendedPool = new ArrayList<>(candidatePool);
    lists.forEach(list -> extendedPool.addAll(list.documents()));
    return new RetrievalState(
        searchQueries,
        libraryFilter,
        metadataFilter,
        metadataFilterExpression,
        mergedLists,
        extendedPool,
        halted);
  }

  /** Marks the run as finished early; every remaining stage is recorded as not reached. */
  public RetrievalState haltRun() {
    return new RetrievalState(
        searchQueries,
        libraryFilter,
        metadataFilter,
        metadataFilterExpression,
        candidateLists,
        candidatePool,
        true);
  }

  /**
   * The run's current selection as a single list.
   *
   * <p>With one list in flight - the normal case from {@link RetrievalStageName#RANK_FUSION} on -
   * that list <em>is</em> the selection. With several, they are collapsed by ordered concatenation
   * deduplicated by chunk id: the deliberate fallback for a run whose fusion stage is switched off,
   * which consequently also loses fusion's {@code top-k} cap. That is what "the pipeline without
   * this stage" means here; it is not a second fusion rule. Since #1049 that uncapped fallback
   * spans up to two lists per search query rather than one, so a fusion-less run can hand on twice
   * as many chunks as before - a benchmark variant, never the shipped configuration.
   */
  public List<Document> selection() {
    if (candidateLists.isEmpty()) {
      return List.of();
    }
    if (candidateLists.size() == 1) {
      return candidateLists.get(0).documents();
    }
    Set<String> seenChunkIds = new LinkedHashSet<>();
    List<Document> collapsed = new ArrayList<>();
    for (CandidateList list : candidateLists) {
      for (Document document : list.documents()) {
        if (seenChunkIds.add(document.getId())) {
          collapsed.add(document);
        }
      }
    }
    return List.copyOf(collapsed);
  }
}
