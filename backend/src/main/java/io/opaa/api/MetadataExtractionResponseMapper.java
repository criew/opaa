package io.opaa.api;

import io.opaa.api.dto.ActiveChatModelSummaryResponse;
import io.opaa.api.dto.LibraryMetadataExtractionSettingsResponse;
import io.opaa.api.dto.LibraryMetadataQualityResponse;
import io.opaa.api.dto.LibraryMetadataSampleResponse;
import io.opaa.api.dto.MetadataFieldQualityResponse;
import io.opaa.api.dto.MetadataModelExtractionStatsResponse;
import io.opaa.api.dto.MetadataSampleDocumentResponse;
import io.opaa.api.dto.MetadataSampleValueResponse;
import io.opaa.indexing.metadata.ChatRoleSummary;
import io.opaa.indexing.metadata.LibraryExtractionSettings;
import io.opaa.indexing.metadata.LibraryMetadataQuality;
import io.opaa.indexing.metadata.LibraryMetadataSample;
import io.opaa.indexing.metadata.ModelExtractionStats;

/**
 * Maps the model-backed extraction's domain records (#1073) onto their generated API types. The
 * chat role is mapped without any access key, and the Stichprobe carries no Schlagwort.
 */
final class MetadataExtractionResponseMapper {

  private MetadataExtractionResponseMapper() {}

  static LibraryMetadataExtractionSettingsResponse toSettingsResponse(
      LibraryExtractionSettings settings) {
    LibraryMetadataExtractionSettingsResponse response =
        new LibraryMetadataExtractionSettingsResponse(
            settings.libraryId(),
            settings.modelExtractionEnabled(),
            settings.keywordsEnabled(),
            settings.confidenceThreshold());
    return response.chatModel(toChatModelResponse(settings.chatRole()));
  }

  private static ActiveChatModelSummaryResponse toChatModelResponse(ChatRoleSummary chatRole) {
    if (chatRole == null) {
      return null;
    }
    return new ActiveChatModelSummaryResponse(
        chatRole.baseUrl(), chatRole.modelIdentifier(), chatRole.local());
  }

  static MetadataModelExtractionStatsResponse toStatsResponse(ModelExtractionStats stats) {
    return new MetadataModelExtractionStatsResponse(
            stats.calls(),
            stats.acceptedValues(),
            stats.rejectedBelowThreshold(),
            stats.rejectedOutsideVocabulary(),
            stats.failures(),
            stats.rejectedPoolFull(),
            stats.keywordsAssigned())
        .lastCallAt(stats.lastCallAt());
  }

  static LibraryMetadataQualityResponse toQualityResponse(LibraryMetadataQuality quality) {
    return new LibraryMetadataQualityResponse(
        quality.libraryId(),
        quality.totalDocuments(),
        quality.modelExtractionEnabled(),
        quality.keywordsEnabled(),
        quality.confidenceThreshold(),
        quality.fields().stream()
            .map(
                field ->
                    new MetadataFieldQualityResponse(
                        field.fieldKey(),
                        field.label(),
                        field.totalDocuments(),
                        field.deterministicDocuments(),
                        field.derivedDocuments(),
                        field.manualDocuments(),
                        field.notDeterminableDocuments(),
                        field.emptyDocuments(),
                        field.derivedShare(),
                        field.emptyShare()))
            .toList(),
        toStatsResponse(quality.modelExtraction()));
  }

  static LibraryMetadataSampleResponse toSampleResponse(LibraryMetadataSample sample) {
    return new LibraryMetadataSampleResponse(
        sample.libraryId(),
        sample.size(),
        sample.documents().stream()
            .map(
                document ->
                    new MetadataSampleDocumentResponse(
                            document.documentId(),
                            document.fileName(),
                            document.values().stream()
                                .map(
                                    value ->
                                        new MetadataSampleValueResponse(
                                                value.fieldKey(), value.label(), value.origin())
                                            .value(value.value())
                                            .confidence(value.confidence())
                                            .modelId(value.modelId()))
                                .toList())
                        .title(document.title()))
            .toList());
  }
}
