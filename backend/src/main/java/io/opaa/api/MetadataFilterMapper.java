package io.opaa.api;

import io.opaa.indexing.metadata.MetadataFilter;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps the generated {@code MetadataFilter} request/response shape onto the domain record and back
 * (#1070; ADR-0006). The domain parse rejects an impossible date with 400; an unknown JSON field is
 * rejected before this mapper runs (see {@link MetadataFilterDeserializer}).
 */
final class MetadataFilterMapper {

  private MetadataFilterMapper() {}

  /** {@link MetadataFilter#NONE} for an absent request object. */
  static MetadataFilter toDomain(io.opaa.api.dto.MetadataFilter request) {
    if (request == null) {
      return MetadataFilter.NONE;
    }
    return MetadataFilter.parse(
        request.getDocumentTypes(), request.getDocumentDateFrom(), request.getDocumentDateTo());
  }

  /**
   * {@code null} for "omitted" on a PATCH, {@link MetadataFilter#NONE} for an object without any
   * condition - the clear signal the chat update draws from emptiness.
   */
  static MetadataFilter toPatchDomain(io.opaa.api.dto.MetadataFilter request) {
    return request == null ? null : toDomain(request);
  }

  /** {@code null} for an empty filter - a client reads "no filter" from the absent object. */
  static io.opaa.api.dto.MetadataFilter toResponse(MetadataFilter filter) {
    if (filter == null || filter.isEmpty()) {
      return null;
    }
    List<String> codes = new ArrayList<>(filter.documentTypes());
    codes.sort(String::compareTo);
    return new io.opaa.api.dto.MetadataFilter()
        .documentTypes(codes.isEmpty() ? null : codes)
        .documentDateFrom(
            filter.documentDateFrom() == null ? null : filter.documentDateFrom().toString())
        .documentDateTo(
            filter.documentDateTo() == null ? null : filter.documentDateTo().toString());
  }
}
