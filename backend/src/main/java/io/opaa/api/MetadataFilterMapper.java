package io.opaa.api;

import io.opaa.api.dto.MetadataFilterFormatFieldCondition;
import io.opaa.api.dto.MetadataFilterLibraryFieldCondition;
import io.opaa.indexing.metadata.FormatFieldCondition;
import io.opaa.indexing.metadata.LibraryFieldCondition;
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
    List<LibraryFieldCondition> conditions = new ArrayList<>();
    if (request.getLibraryFields() != null) {
      for (MetadataFilterLibraryFieldCondition condition : request.getLibraryFields()) {
        conditions.add(
            LibraryFieldCondition.parse(
                condition.getLibraryId(),
                condition.getFieldKey(),
                condition.getCodes(),
                condition.getDateFrom(),
                condition.getDateTo(),
                condition.getValue()));
      }
    }
    List<FormatFieldCondition> formatConditions = new ArrayList<>();
    if (request.getFormatFields() != null) {
      for (MetadataFilterFormatFieldCondition condition : request.getFormatFields()) {
        formatConditions.add(
            FormatFieldCondition.parse(condition.getFieldKey(), condition.getValues()));
      }
    }
    return MetadataFilter.parse(
            request.getDocumentTypes(), request.getDocumentDateFrom(), request.getDocumentDateTo())
        .withLibraryFields(conditions)
        .withFormatFields(formatConditions);
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
        .documentDateTo(filter.documentDateTo() == null ? null : filter.documentDateTo().toString())
        .libraryFields(
            filter.libraryFields().isEmpty()
                ? null
                : filter.libraryFields().stream()
                    .map(MetadataFilterMapper::toConditionResponse)
                    .toList())
        .formatFields(
            filter.formatFields().isEmpty()
                ? null
                : filter.formatFields().stream()
                    .map(MetadataFilterMapper::toConditionResponse)
                    .toList());
  }

  private static MetadataFilterFormatFieldCondition toConditionResponse(
      FormatFieldCondition condition) {
    return new MetadataFilterFormatFieldCondition(
        condition.fieldKey(), condition.values().stream().sorted().toList());
  }

  private static MetadataFilterLibraryFieldCondition toConditionResponse(
      LibraryFieldCondition condition) {
    MetadataFilterLibraryFieldCondition response =
        new MetadataFilterLibraryFieldCondition(condition.libraryId(), condition.fieldKey());
    if (!condition.codes().isEmpty()) {
      response.setCodes(condition.codes().stream().sorted().toList());
    }
    if (condition.dateFrom() != null) {
      response.setDateFrom(condition.dateFrom().toString());
    }
    if (condition.dateTo() != null) {
      response.setDateTo(condition.dateTo().toString());
    }
    response.setValue(condition.value());
    return response;
  }
}
