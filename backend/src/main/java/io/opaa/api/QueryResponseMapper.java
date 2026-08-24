package io.opaa.api;

import io.opaa.api.dto.QueryMetadata;
import io.opaa.api.dto.QueryResponse;
import io.opaa.api.dto.SearchedLibrary;
import io.opaa.query.QueryOutcome;
import io.opaa.query.QueryResult;
import io.opaa.query.SearchedLibraryRef;
import java.util.List;

/**
 * Maps {@link QueryResult} and {@link QueryOutcome} onto their generated response counterparts
 * (ADR-0006: API DTOs are generated from the specification, never hand-written). Source mapping
 * itself is shared with {@link ChatResponseMapper}, since {@code QueryService} and {@code
 * ChatService} produce/consume the identical {@code ChatSource} domain shape.
 */
final class QueryResponseMapper {

  private QueryResponseMapper() {}

  static QueryResponse toResponse(QueryResult result) {
    return new QueryResponse(
            result.getAnswer(),
            ChatResponseMapper.toSourceReferences(result.getSources()),
            toMetadata(result.getMetadata()),
            result.getChatId())
        .chatTitle(result.getChatTitle());
  }

  private static QueryMetadata toMetadata(QueryOutcome outcome) {
    return new QueryMetadata(outcome.getModel(), outcome.getTokenCount(), outcome.getDurationMs())
        .answeredWithoutKnowledge(outcome.getAnsweredWithoutKnowledge())
        .noKnowledgeAvailableInSpace(outcome.getNoKnowledgeAvailableInSpace())
        .searchedLibraries(toSearchedLibraries(outcome.getSearchedLibraries()));
  }

  private static List<SearchedLibrary> toSearchedLibraries(List<SearchedLibraryRef> refs) {
    return refs == null
        ? null
        : refs.stream().map(ref -> new SearchedLibrary(ref.getId(), ref.getName())).toList();
  }
}
