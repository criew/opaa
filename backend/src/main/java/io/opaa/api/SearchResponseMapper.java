package io.opaa.api;

import io.opaa.api.dto.SearchHitContentResponse;
import io.opaa.api.dto.SearchResponse;
import io.opaa.api.dto.SearchedLibrary;
import io.opaa.query.SearchedLibraryRef;
import io.opaa.search.FetchedPassage;
import io.opaa.search.SearchHit;
import io.opaa.search.SearchOutcome;
import java.util.List;
import java.util.UUID;

/**
 * Maps the reading path's domain records onto their generated response counterparts (ADR-0006: API
 * DTOs are generated from the specification, never hand-written). The metadata list is mapped by
 * {@link ChatResponseMapper}, so a hit and the Beleg of the same passage render identically.
 */
final class SearchResponseMapper {

  /** The path a client opens the original behind a hit at; relative to this API. */
  private static final String DOCUMENT_CONTENT_PATH = "/api/v1/documents/%s/content";

  private SearchResponseMapper() {}

  static SearchResponse toResponse(SearchOutcome outcome) {
    return new SearchResponse(
        outcome.hits().stream().map(SearchResponseMapper::toHit).toList(),
        toSearchedLibraries(outcome.searchedLibraries()));
  }

  private static io.opaa.api.dto.SearchHit toHit(SearchHit hit) {
    return new io.opaa.api.dto.SearchHit(
            hit.hitId(), hit.title(), hit.excerpt(), hit.relevanceScore())
        .libraryId(hit.libraryId())
        .libraryName(hit.libraryName())
        .documentId(hit.documentId())
        .fileName(hit.fileName())
        .chunkIndex(hit.chunkIndex())
        .location(hit.location())
        .metadata(ChatResponseMapper.toMetadataEntries(hit.metadata()))
        .downloadUrl(downloadUrl(hit.documentId(), hit.downloadable()));
  }

  static SearchHitContentResponse toResponse(FetchedPassage passage) {
    return new SearchHitContentResponse(
            passage.hitId(),
            passage.text(),
            passage.whole(),
            passage.truncated(),
            passage.characterLimit())
        .documentId(passage.documentId())
        .fileName(passage.fileName())
        .title(passage.title())
        .libraryId(passage.libraryId())
        .libraryName(passage.libraryName())
        .chunkIndex(passage.chunkIndex())
        .location(passage.location())
        .headingPath(passage.headingPath())
        .metadata(ChatResponseMapper.toMetadataEntries(passage.metadata()))
        .downloadUrl(downloadUrl(passage.documentId(), passage.downloadable()));
  }

  /**
   * The download path, or null. {@code downloadable} is decided in the domain against the effective
   * view of the request; the mapper never derives it from the mere presence of a document id, so
   * the path cannot be handed out past the scope.
   */
  private static String downloadUrl(UUID documentId, boolean downloadable) {
    return documentId == null || !downloadable ? null : DOCUMENT_CONTENT_PATH.formatted(documentId);
  }

  static List<io.opaa.api.dto.SearchableLibrary> toLibraries(
      List<io.opaa.search.SearchableLibrary> libraries) {
    return libraries.stream()
        .map(
            library ->
                new io.opaa.api.dto.SearchableLibrary(library.id(), library.name())
                    .description(library.description()))
        .toList();
  }

  private static List<SearchedLibrary> toSearchedLibraries(List<SearchedLibraryRef> refs) {
    return refs == null
        ? List.of()
        : refs.stream().map(ref -> new SearchedLibrary(ref.id(), ref.name())).toList();
  }
}
