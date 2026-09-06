package io.opaa.api;

import io.opaa.api.dto.CoreContextPrefixResponse;
import io.opaa.api.dto.CreateLibraryMetadataFieldRequest;
import io.opaa.api.dto.EmbeddingRateSource;
import io.opaa.api.dto.LibraryMetadataFieldResponse;
import io.opaa.api.dto.LibraryMetadataFieldValueRequest;
import io.opaa.api.dto.LibraryMetadataFieldValueResponse;
import io.opaa.api.dto.LibraryMetadataFieldsResponse;
import io.opaa.api.dto.MetadataChangeImpactResponse;
import io.opaa.api.dto.MetadataFieldUsageResponse;
import io.opaa.api.dto.RemapLibraryMetadataFieldValueResponse;
import io.opaa.indexing.EmbeddingRateEstimator;
import io.opaa.indexing.metadata.CoreContextPrefixSettings;
import io.opaa.indexing.metadata.LibraryFieldValueRemapResult;
import io.opaa.indexing.metadata.LibraryMetadataField;
import io.opaa.indexing.metadata.LibraryMetadataFieldDefinition;
import io.opaa.indexing.metadata.LibraryMetadataFieldInput;
import io.opaa.indexing.metadata.LibraryMetadataFieldOverview;
import io.opaa.indexing.metadata.LibraryMetadataFieldValue;
import io.opaa.indexing.metadata.MetadataChangeImpact;
import java.util.List;

/**
 * Maps the library metadata field schema onto its generated API types and the request bodies onto
 * the domain input records (: the domain services never see a DTO).
 */
final class LibraryMetadataFieldResponseMapper {

  private LibraryMetadataFieldResponseMapper() {}

  static LibraryMetadataFieldsResponse toResponse(LibraryMetadataFieldOverview overview) {
    return new LibraryMetadataFieldsResponse(
        overview.fields().stream()
            .map(LibraryMetadataFieldResponseMapper::toFieldResponse)
            .toList(),
        toCoreContextPrefixResponse(overview.coreContextPrefix()),
        overview.documentsAwaitingContextPrefixRerun());
  }

  static CoreContextPrefixResponse toCoreContextPrefixResponse(CoreContextPrefixSettings settings) {
    return new CoreContextPrefixResponse(
        settings.title(), settings.documentType(), settings.documentDate());
  }

  static MetadataChangeImpactResponse toImpactResponse(MetadataChangeImpact impact) {
    return new MetadataChangeImpactResponse(
        impact.affectedDocuments(),
        impact.affectedChunks(),
        impact.embeddingCalls(),
        impact.estimatedSeconds(),
        impact.reembeddingRequired(),
        impact.rateSource() == EmbeddingRateEstimator.RateSource.MEASURED
            ? EmbeddingRateSource.MEASURED
            : EmbeddingRateSource.CONFIGURED);
  }

  static LibraryMetadataFieldResponse toFieldResponse(LibraryMetadataFieldDefinition definition) {
    LibraryMetadataField field = definition.field();
    return new LibraryMetadataFieldResponse(
            field.getFieldKey(),
            field.documentFieldKey(),
            field.getLabel(),
            field.getType(),
            field.isFilterEnabled(),
            field.isContextPrefixEnabled(),
            field.getSortOrder(),
            definition.values().stream()
                .map(LibraryMetadataFieldResponseMapper::toValueResponse)
                .toList())
        .valuePattern(field.getValuePattern())
        .citationPosition(field.getCitationPosition());
  }

  static LibraryMetadataFieldValueResponse toValueResponse(LibraryMetadataFieldValue value) {
    return new LibraryMetadataFieldValueResponse(value.getCode(), value.getLabel());
  }

  static MetadataFieldUsageResponse toUsageResponse(long documentCount) {
    return new MetadataFieldUsageResponse(documentCount);
  }

  static RemapLibraryMetadataFieldValueResponse toRemapResponse(
      LibraryFieldValueRemapResult result) {
    return new RemapLibraryMetadataFieldValueResponse(
        result.remappedDocuments(), result.clearedDocuments(), result.correlationRef());
  }

  static LibraryMetadataFieldInput toInput(CreateLibraryMetadataFieldRequest request) {
    return new LibraryMetadataFieldInput(
        request.getFieldKey(),
        request.getLabel(),
        request.getType(),
        request.getValuePattern(),
        Boolean.TRUE.equals(request.getFilter()),
        Boolean.TRUE.equals(request.getContextPrefix()),
        request.getCitationPosition(),
        request.getValues() == null
            ? List.of()
            : request.getValues().stream()
                .map(LibraryMetadataFieldResponseMapper::toValueInput)
                .toList());
  }

  private static LibraryMetadataFieldInput.LibraryFieldValueInput toValueInput(
      LibraryMetadataFieldValueRequest request) {
    return new LibraryMetadataFieldInput.LibraryFieldValueInput(
        request.getCode(), request.getLabel());
  }
}
