package io.opaa.search;

import io.opaa.auth.CurrentUser;
import io.opaa.externalaccess.ExternalAccessMassRetrievalAlarm;
import io.opaa.externalaccess.ExternalAccessQuota;
import io.opaa.indexing.metadata.MetadataFilter;
import io.opaa.indexing.metadata.MetadataFilterValidator;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.query.KnowledgeRetrieval;
import io.opaa.query.SearchScopeResolver;
import io.opaa.query.SearchedLibraryRef;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/**
 * Hits instead of an answer (#1720, docs/features/external-access.md): the same retrieval {@code
 * QueryService} builds its answer from, returned as passages.
 *
 * <p>The invariant of this class is what it does <em>not</em> do. It resolves no scope of its own -
 * that is {@link SearchScopeSource} -, it ranks nothing of its own - that is {@link
 * KnowledgeRetrieval}, the one entrance {@code POST /api/v1/query} uses as well -, it never touches
 * {@code vector_store} directly, and it asks no model to generate anything. A question searched
 * here therefore returns exactly the passages the same question would have been answered from, in
 * the same order.
 *
 * <p>Deliberately not {@code @Transactional}, for the reason {@code QueryService#query} gives: the
 * retrieval includes a model round trip, and an ambient transaction would hold a JDBC connection
 * across it.
 */
@Service
public class SearchService {

  private final KnowledgeRetrieval knowledgeRetrieval;
  private final SearchScopeSource searchScopeSource;
  private final SearchScopeResolver searchScopeResolver;
  private final MetadataFilterValidator metadataFilterValidator;
  private final SearchHitAssembler hitAssembler;
  private final SearchProperties properties;
  private final ExternalAccessQuota quota;
  private final ExternalAccessMassRetrievalAlarm alarm;
  private final KnowledgeLibraryRepository libraries;

  public SearchService(
      KnowledgeRetrieval knowledgeRetrieval,
      SearchScopeSource searchScopeSource,
      SearchScopeResolver searchScopeResolver,
      MetadataFilterValidator metadataFilterValidator,
      SearchHitAssembler hitAssembler,
      SearchProperties properties,
      ExternalAccessQuota quota,
      ExternalAccessMassRetrievalAlarm alarm,
      KnowledgeLibraryRepository libraries) {
    this.knowledgeRetrieval = knowledgeRetrieval;
    this.searchScopeSource = searchScopeSource;
    this.searchScopeResolver = searchScopeResolver;
    this.metadataFilterValidator = metadataFilterValidator;
    this.hitAssembler = hitAssembler;
    this.properties = properties;
    this.quota = quota;
    this.alarm = alarm;
    this.libraries = libraries;
  }

  /**
   * The libraries of {@code caller}'s effective view - identifier, name and description, sorted by
   * name. For a signed-in person that is every library she may read; for an access token it is the
   * intersection of rights, release and token selection, because it comes from the same {@link
   * SearchScopeSource} the search itself uses. It is the only place a foreign tool learns the
   * extent of its access, and {@code list_libraries} of the MCP server (#1721) is built on it.
   *
   * <p>Counts against the token quota and the channel alert like any other retrieval: it is a read
   * of the holdings' structure, not a free call.
   */
  public List<SearchableLibrary> libraries(CurrentUser caller) {
    SearchRequestScope scope = searchScopeSource.scopeFor(caller);
    quota.requireWithinQuota(scope.accessTokenId());
    alarm.record(caller.organizationId(), scope.accessTokenId());
    return librariesOf(scope);
  }

  /**
   * The same effective view, resolved through the same {@link SearchScopeSource}: the MCP server
   * builds its tool descriptions from it on every {@code tools/list} (#1721).
   *
   * <p><b>Counts against the quota like any other call</b> - {@code /mcp} lies outside the request
   * rate limit of {@code /api/*}, so the quota is the only brake this channel has, and an uncounted
   * path would be an unbounded one. It does <b>not</b> feed the mass-retrieval alert: that watches
   * holdings leaving the house, and a listing of names a token may see is not a retrieval.
   */
  public List<SearchableLibrary> librariesForDescription(CurrentUser caller) {
    SearchRequestScope scope = searchScopeSource.scopeFor(caller);
    quota.requireWithinQuota(scope.accessTokenId());
    return librariesOf(scope);
  }

  private List<SearchableLibrary> librariesOf(SearchRequestScope scope) {
    if (scope.libraryIds().isEmpty()) {
      return List.of();
    }
    return libraries.findAllById(scope.libraryIds()).stream()
        .map(
            library ->
                new SearchableLibrary(library.getId(), library.getName(), library.getDescription()))
        .sorted(Comparator.comparing(SearchableLibrary::name, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  /**
   * The hits for {@code question} within {@code caller}'s effective view, narrowed to {@code
   * requestedLibraryIds} when given - intersected, never widened, so a referenced but unreachable
   * library simply yields nothing. {@code requestedMaxHits} is capped server-side rather than
   * rejected. An unknown Dokumentart code or an unknown library field in {@code metadataFilter} is
   * a caller error (400), never silently a filter that matches nothing.
   */
  public SearchOutcome search(
      CurrentUser caller,
      String question,
      List<UUID> requestedLibraryIds,
      MetadataFilter requestedMetadataFilter,
      Integer requestedMaxHits) {
    SearchRequestScope scope = searchScopeSource.scopeFor(caller);
    // Both are no-ops for a signed-in person: the quota is the token's, and the alert watches the
    // external-access channel, not the web interface.
    quota.requireWithinQuota(scope.accessTokenId());
    alarm.record(caller.organizationId(), scope.accessTokenId());
    Set<UUID> effectiveView = scope.libraryIds();
    boolean everything = requestedLibraryIds == null || requestedLibraryIds.isEmpty();
    // The same resolver the query uses for an ephemeral question, so "no narrowing" and "narrowed
    // to these" mean here exactly what they mean there.
    Set<UUID> searchScope =
        searchScopeResolver.resolveSearchScope(
            Optional.empty(), everything, requestedLibraryIds, effectiveView);
    MetadataFilter metadataFilter = validated(effectiveView, requestedMetadataFilter);

    List<SearchedLibraryRef> searchedLibraries = namesOf(searchScope);
    if (searchScope.isEmpty()) {
      // No search at all rather than a search over nothing - the same short circuit the query
      // takes, and the reason an empty view is indistinguishable from an empty result.
      return new SearchOutcome(List.of(), searchedLibraries);
    }

    List<Document> chunks =
        knowledgeRetrieval
            .retrieve(question, List.of(), List.of(), searchScope, metadataFilter)
            .chunks();
    int limit = Math.min(properties.effectiveMaxHits(requestedMaxHits), chunks.size());
    return new SearchOutcome(
        hitAssembler.assemble(chunks.subList(0, limit), searchScope, scope.accessTokenId() == null),
        searchedLibraries);
  }

  /**
   * The libraries the search actually ran against, by name and sorted - resolved from the effective
   * scope, never from the request, so the line reflects permissions exactly as the search did.
   */
  private List<SearchedLibraryRef> namesOf(Set<UUID> searchScope) {
    if (searchScope.isEmpty()) {
      return List.of();
    }
    return libraries.findAllById(searchScope).stream()
        .map(library -> new SearchedLibraryRef(library.getId(), library.getName()))
        .sorted(Comparator.comparing(SearchedLibraryRef::name, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  private MetadataFilter validated(Set<UUID> effectiveView, MetadataFilter filter) {
    if (filter == null || filter.isEmpty()) {
      return MetadataFilter.NONE;
    }
    return metadataFilterValidator.validate(filter, effectiveView);
  }
}
