package io.opaa.library;

import io.opaa.api.types.CatalogEntryStatus;
import io.opaa.api.types.DocumentStatus;
import io.opaa.asset.AssetCatalogFactSource;
import io.opaa.asset.AssetCatalogFacts;
import io.opaa.connection.LibraryConnectionService;
import io.opaa.indexing.job.IndexingJobRepository;
import io.opaa.indexing.job.JobStatus;
import io.opaa.indexing.source.SourceBlock;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.SourceType;
import io.opaa.permission.AssetType;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * A knowledge library's catalog facts: source type, the "Stand" of the library list and its status.
 * A connector library goes by its newest indexing run; an upload library has no runs and goes by
 * its documents - one being processed, one failed, one indexed, none at all, first match wins. A
 * locked source adds its notice; the status stays the indexing one.
 */
@Component
class KnowledgeLibraryCatalogFactSource implements AssetCatalogFactSource {

  private final KnowledgeLibraryRepository libraryRepository;
  private final IndexingJobRepository indexingJobRepository;
  private final DocumentRepository documentRepository;
  private final LibraryConnectionService libraryConnections;

  KnowledgeLibraryCatalogFactSource(
      KnowledgeLibraryRepository libraryRepository,
      IndexingJobRepository indexingJobRepository,
      DocumentRepository documentRepository,
      LibraryConnectionService libraryConnections) {
    this.libraryRepository = libraryRepository;
    this.indexingJobRepository = indexingJobRepository;
    this.documentRepository = documentRepository;
    this.libraryConnections = libraryConnections;
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
    List<KnowledgeLibrary> libraries = libraryRepository.findAllById(assetIds);
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
    Set<UUID> uploadIds =
        libraries.stream()
            .filter(library -> SourceType.UPLOAD.equals(library.getSourceType()))
            .map(KnowledgeLibrary::getId)
            .collect(Collectors.toSet());
    Set<UUID> pending = librariesWith(uploadIds, DocumentStatus.PENDING);
    Set<UUID> failed = librariesWith(uploadIds, DocumentStatus.FAILED);
    Set<UUID> indexed = librariesWith(uploadIds, DocumentStatus.INDEXED);
    Map<UUID, SourceBlock> locks = libraryConnections.locksOf(libraries);

    Map<UUID, AssetCatalogFacts> facts = new HashMap<>();
    for (KnowledgeLibrary library : libraries) {
      UUID id = library.getId();
      CatalogEntryStatus status =
          uploadIds.contains(id)
              ? uploadStatusOf(pending.contains(id), failed.contains(id), indexed.contains(id))
              : connectorStatusOf(lastRunStatus.get(id));
      facts.put(
          id,
          new KnowledgeLibraryCatalogFacts(
              library.getSourceType().key(),
              lastIndexedAt.get(id),
              status,
              locks.get(id),
              library.isOwnerOnly()));
    }
    return facts;
  }

  private Set<UUID> librariesWith(Set<UUID> libraryIds, DocumentStatus status) {
    if (libraryIds.isEmpty()) {
      return Set.of();
    }
    return documentRepository.countByLibraryAndStatus(libraryIds, status).stream()
        .map(DocumentRepository.LibraryDocumentCount::getLibraryId)
        .collect(Collectors.toSet());
  }

  static CatalogEntryStatus connectorStatusOf(JobStatus newestRun) {
    if (newestRun == null) {
      return CatalogEntryStatus.NOT_YET_AVAILABLE;
    }
    return switch (newestRun) {
      case RUNNING -> CatalogEntryStatus.UPDATING;
      case FAILED -> CatalogEntryStatus.UPDATE_FAILED;
      case COMPLETED -> CatalogEntryStatus.READY;
    };
  }

  static CatalogEntryStatus uploadStatusOf(boolean pending, boolean failed, boolean indexed) {
    if (pending) {
      return CatalogEntryStatus.UPDATING;
    }
    if (failed) {
      return CatalogEntryStatus.UPDATE_FAILED;
    }
    return indexed ? CatalogEntryStatus.READY : CatalogEntryStatus.NOT_YET_AVAILABLE;
  }
}
