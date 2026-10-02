package io.opaa.library;

import io.opaa.asset.AssetCatalogFactSource;
import io.opaa.asset.AssetCatalogFacts;
import io.opaa.asset.AssetCatalogStatus;
import io.opaa.indexing.job.IndexingJobRepository;
import io.opaa.indexing.job.JobStatus;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.SourceType;
import io.opaa.permission.AssetType;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * A knowledge library's catalog facts: source type, the "Stand" of the library list and a status by
 * its newest indexing run - running, failed or completed. A connector library without any run is
 * not yet available; an upload library has no runs and is ready.
 */
@Component
class KnowledgeLibraryCatalogFactSource implements AssetCatalogFactSource {

  private final KnowledgeLibraryRepository libraryRepository;
  private final IndexingJobRepository indexingJobRepository;

  KnowledgeLibraryCatalogFactSource(
      KnowledgeLibraryRepository libraryRepository, IndexingJobRepository indexingJobRepository) {
    this.libraryRepository = libraryRepository;
    this.indexingJobRepository = indexingJobRepository;
  }

  @Override
  public AssetType assetType() {
    return KnowledgeLibrary.ASSET_TYPE;
  }

  @Override
  public Map<UUID, AssetCatalogFacts> factsOf(Collection<UUID> assetIds) {
    if (assetIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, Instant> lastIndexedAt =
        indexingJobRepository.findLastCompletedByLibraryIdIn(assetIds).stream()
            .collect(
                Collectors.toMap(
                    IndexingJobRepository.LibraryLastCompleted::getLibraryId,
                    IndexingJobRepository.LibraryLastCompleted::getLastCompletedAt));
    Map<UUID, JobStatus> lastRunStatus =
        indexingJobRepository.findLastRunStatusByLibraryIdIn(assetIds).stream()
            .collect(
                Collectors.toMap(
                    IndexingJobRepository.LibraryLastRunStatus::getLibraryId,
                    status -> JobStatus.valueOf(status.getStatus())));
    Map<UUID, AssetCatalogFacts> facts = new HashMap<>();
    for (KnowledgeLibrary library : libraryRepository.findAllById(assetIds)) {
      UUID id = library.getId();
      facts.put(
          id,
          new KnowledgeLibraryCatalogFacts(
              library.getSourceType().key(),
              lastIndexedAt.get(id),
              statusOf(library.getSourceType(), lastRunStatus.get(id))));
    }
    return facts;
  }

  static AssetCatalogStatus statusOf(SourceType sourceType, JobStatus newestRun) {
    if (newestRun == null) {
      return SourceType.UPLOAD.equals(sourceType)
          ? AssetCatalogStatus.READY
          : AssetCatalogStatus.NOT_YET_AVAILABLE;
    }
    return switch (newestRun) {
      case RUNNING -> AssetCatalogStatus.UPDATING;
      case FAILED -> AssetCatalogStatus.UPDATE_FAILED;
      case COMPLETED -> AssetCatalogStatus.READY;
    };
  }
}
