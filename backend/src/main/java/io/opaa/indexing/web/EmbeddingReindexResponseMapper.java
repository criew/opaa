package io.opaa.indexing.web;

import io.opaa.api.dto.EmbeddingIndexIdentity;
import io.opaa.api.dto.EmbeddingReindexRunKind;
import io.opaa.api.dto.EmbeddingReindexRunResponse;
import io.opaa.api.dto.EmbeddingReindexRunStatus;
import io.opaa.api.dto.EmbeddingReindexStatusResponse;
import io.opaa.indexing.chunk.EmbeddingIdentity;
import io.opaa.indexing.chunk.EmbeddingIndexRecord;
import io.opaa.indexing.maintenance.EmbeddingReindexProgress;

/** Maps the state of the vector index and its re-embedding onto the API response. */
final class EmbeddingReindexResponseMapper {

  private EmbeddingReindexResponseMapper() {}

  static EmbeddingReindexStatusResponse toResponse(EmbeddingReindexProgress progress) {
    EmbeddingReindexStatusResponse response =
        new EmbeddingReindexStatusResponse(
            toIdentity(progress.current()),
            toIdentity(progress.configured()),
            progress.stale(),
            progress.running(),
            progress.totalChunks(),
            progress.embeddedChunks(),
            progress.privateLibrariesCounted());
    response
        .previous(toIdentity(progress.previous()))
        .switching(progress.switching())
        .withoutCurrentVector(progress.withoutCurrentVector());
    EmbeddingIndexRecord.Run run = progress.lastRun();
    if (run != null && run.status() != null) {
      response.lastRun(
          new EmbeddingReindexRunResponse(EmbeddingReindexRunStatus.valueOf(run.status().name()))
              .kind(EmbeddingReindexRunKind.valueOf(run.kindOrReembed().name()))
              .startedAt(run.startedAt())
              .finishedAt(run.finishedAt())
              .message(run.message()));
    }
    return response;
  }

  private static EmbeddingIndexIdentity toIdentity(EmbeddingIdentity identity) {
    if (identity == null) {
      return null;
    }
    return new EmbeddingIndexIdentity(identity.dimensions())
        .model(identity.model())
        .baseUrl(identity.baseUrl())
        .generation(identity.generation());
  }
}
