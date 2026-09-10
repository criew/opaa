package io.opaa.query.retrieval;

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
 * <p>{@code candidatePool} is the ceiling of the whole run: only a search stage may extend it, and
 * every other stage draws from it - the invariant that keeps document completion from reaching past
 * the permission filter the searches applied.
 *
 * <p>Immutable: a stage returns a new state rather than mutating this one, so a stage cannot alter
 * what an earlier stage recorded in the explanation protocol.
 *
 * @param searchQueries the queries the search stages run, one each; empty before {@link
 *     RetrievalStageName#SUB_QUERY_DECOMPOSITION} ran or when that stage is switched off.
 * @param libraryFilter the {@code library_id IN (...)} filter {@link
 *     RetrievalStageName#SEARCH_SCOPE} built; {@code null} only before that stage ran.
 * @param metadataFilter the core-field filter {@link RetrievalStageName#METADATA_FILTER} carried
 *     into the run; {@link MetadataFilter#NONE} before that stage ran, when it is switched off, or
 *     when the caller set none. Never a widening of the permission filter: every search stage AND-s
 *     it to {@link #libraryFilter}.
 * @param metadataFilterExpression the vector-path form of {@link #metadataFilter}; {@code null}
 *     exactly when that filter is empty.
 * @param metadataFilterVocabularyCodes the Dokumentart vocabulary snapshot both filter forms say
 *     "no value" over, read once by {@link RetrievalStageName#METADATA_FILTER} so every sub-query
 *     of the lexical path filters against the same set as the vector path; empty without a filter.
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
    List<String> metadataFilterVocabularyCodes,
    List<CandidateList> candidateLists,
    List<Document> candidatePool,
    boolean halted) {

  public RetrievalState {
    searchQueries = List.copyOf(searchQueries);
    metadataFilter = metadataFilter == null ? MetadataFilter.NONE : metadataFilter;
    metadataFilterVocabularyCodes =
        metadataFilterVocabularyCodes == null
            ? List.of()
            : List.copyOf(metadataFilterVocabularyCodes);
    candidateLists = List.copyOf(candidateLists);
    candidatePool = List.copyOf(candidatePool);
  }

  /** The state a run starts in: no queries, no filter, no candidates. */
  public static RetrievalState initial() {
    return new RetrievalState(
        List.of(), null, MetadataFilter.NONE, null, List.of(), List.of(), List.of(), false);
  }

  public RetrievalState withSearchQueries(List<String> queries) {
    return new RetrievalState(
        queries,
        libraryFilter,
        metadataFilter,
        metadataFilterExpression,
        metadataFilterVocabularyCodes,
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
        metadataFilterVocabularyCodes,
        candidateLists,
        candidatePool,
        halted);
  }

  /**
   * Carries the metadata filter into the run, in both forms the two search paths need plus the
   * vocabulary snapshot they were built over - what {@link RetrievalStageName#METADATA_FILTER}
   * does. The permission filter is untouched.
   */
  public RetrievalState withMetadataFilter(
      MetadataFilter filter, Filter.Expression expression, List<String> vocabularyCodes) {
    return new RetrievalState(
        searchQueries,
        libraryFilter,
        filter,
        expression,
        vocabularyCodes,
        candidateLists,
        candidatePool,
        halted);
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
        metadataFilterVocabularyCodes,
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
        metadataFilterVocabularyCodes,
        mergedLists,
        extendedPool,
        halted);
  }

  /**
   * How many candidates are currently in flight, counted across all lists - the number a stage
   * reports as its incoming count. Counts a chunk once per list it appears in, because that is what
   * the lists hold before fusion deduplicates them.
   */
  public int candidateCount() {
    return candidateLists.stream().mapToInt(list -> list.documents().size()).sum();
  }

  /**
   * The filter a search stage must apply, or an {@link IllegalStateException} - a search without a
   * permission filter is not a degraded mode this pipeline has.
   */
  public Filter.Expression requiredLibraryFilter() {
    if (libraryFilter == null) {
      throw new IllegalStateException(
          "no permission filter in the pipeline state: a search stage ran before "
              + RetrievalStageName.SEARCH_SCOPE
              + ", which would search without a rights filter (ADR-0008 §5)");
    }
    return libraryFilter;
  }

  /** Marks the run as finished early; every remaining stage is recorded as not reached. */
  public RetrievalState haltRun() {
    return new RetrievalState(
        searchQueries,
        libraryFilter,
        metadataFilter,
        metadataFilterExpression,
        metadataFilterVocabularyCodes,
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
   * which consequently also loses fusion's {@code top-k} cap and spans up to two lists per search
   * query. A benchmark variant, never the shipped configuration, and not a second fusion rule.
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
