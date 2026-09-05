package io.opaa.api;

import io.opaa.api.dto.CoreMetadataFieldFillResponse;
import io.opaa.api.dto.MetadataBackfillResponse;
import io.opaa.api.dto.MetadataBackfillStatusResponse;
import io.opaa.indexing.metadata.CoreMetadataExtractor;
import io.opaa.indexing.metadata.CoreMetadataField;
import io.opaa.indexing.metadata.MetadataBackfillProgress;
import io.opaa.indexing.metadata.MetadataBackfillResult;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps the core-metadata backfill's domain records (#1067) onto their generated API responses
 * (#860: the domain services never see a DTO). Package-private like every other mapper here.
 */
final class MetadataBackfillResponseMapper {

  private MetadataBackfillResponseMapper() {}

  static MetadataBackfillResponse toBackfillResponse(MetadataBackfillResult result) {
    return new MetadataBackfillResponse(
        result.processedDocuments(),
        result.markedForNextRun(),
        result.skippedDocuments(),
        result.isEmpty());
  }

  static MetadataBackfillStatusResponse toStatusResponse(MetadataBackfillProgress progress) {
    List<CoreMetadataFieldFillResponse> fields = new ArrayList<>();
    for (CoreMetadataField field : CoreMetadataField.values()) {
      fields.add(
          new CoreMetadataFieldFillResponse(
              field.key(),
              field.label(),
              progress.filledDocuments(field),
              progress.filledShare(field),
              progress.notDeterminableDocuments(field),
              progress.documentsWithoutValue(field),
              progress.missingShare(field)));
    }
    return new MetadataBackfillStatusResponse(
        CoreMetadataExtractor.EXTRACTION_VERSION,
        progress.totalDocuments(),
        progress.currentDocuments(),
        progress.pendingDocuments(),
        progress.awaitingConnectorRunDocuments(),
        progress.lastSkippedDocuments(),
        progress.isComplete(),
        fields);
  }
}
