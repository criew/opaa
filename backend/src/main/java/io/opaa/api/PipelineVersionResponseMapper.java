package io.opaa.api;

import io.opaa.api.dto.DocumentPipelineResponse;
import io.opaa.api.dto.LibraryPipelineVersionResponse;
import io.opaa.api.dto.PipelineReindexResponse;
import io.opaa.api.dto.PipelineVersionStatusResponse;
import io.opaa.indexing.PipelineReindexResult;
import io.opaa.indexing.PipelineVersionProgress;
import io.opaa.indexing.pipeline.DocumentPipeline;
import java.util.Collection;
import java.util.List;

/**
 * Maps {@code io.opaa.indexing}'s pipeline-version domain types onto their generated API responses
 * (#860: the domain services never see a DTO). Package-private, like every other response mapper in
 * this package.
 */
final class PipelineVersionResponseMapper {

  private PipelineVersionResponseMapper() {}

  static PipelineVersionStatusResponse toStatusResponse(
      Collection<DocumentPipeline> pipelines, List<PipelineVersionProgress> progress) {
    PipelineVersionStatusResponse response = new PipelineVersionStatusResponse();
    response.setPipelines(
        pipelines.stream().map(PipelineVersionResponseMapper::toPipelineResponse).toList());
    response.setLibraries(
        progress.stream().map(PipelineVersionResponseMapper::toLibraryResponse).toList());
    return response;
  }

  private static DocumentPipelineResponse toPipelineResponse(DocumentPipeline pipeline) {
    DocumentPipelineResponse response = new DocumentPipelineResponse();
    response.setId(pipeline.id());
    response.setCurrentVersion((int) pipeline.version());
    response.setHandledFormats(pipeline.handledFormats().stream().sorted().toList());
    return response;
  }

  private static LibraryPipelineVersionResponse toLibraryResponse(
      PipelineVersionProgress progress) {
    LibraryPipelineVersionResponse response = new LibraryPipelineVersionResponse();
    response.setLibraryId(progress.libraryId());
    response.setTotalChunks(progress.totalChunks());
    response.setCurrentVersionChunks(progress.currentVersionChunks());
    response.setStaleChunks(progress.staleChunks());
    response.setComplete(progress.isComplete());
    return response;
  }

  static PipelineReindexResponse toReindexResponse(PipelineReindexResult result) {
    PipelineReindexResponse response = new PipelineReindexResponse();
    response.setReindexedDocuments(result.reindexedDocuments());
    response.setMarkedForNextRun(result.markedForNextRun());
    response.setSkippedDocuments(result.skippedDocuments());
    response.setRemovedOrphanChunkSets(result.removedOrphanChunkSets());
    response.setDone(result.isEmpty());
    return response;
  }
}
