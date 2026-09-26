package io.opaa.library;

import io.opaa.asset.AssetExtent;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.permission.AssetType;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** A knowledge library's extent: its top-level documents, as the library overview counts them. */
@Component
class KnowledgeLibraryExtent implements AssetExtent {

  private final DocumentRepository documentRepository;

  KnowledgeLibraryExtent(DocumentRepository documentRepository) {
    this.documentRepository = documentRepository;
  }

  @Override
  public AssetType assetType() {
    return KnowledgeLibrary.ASSET_TYPE;
  }

  @Override
  public Map<UUID, Long> itemCounts(Collection<UUID> assetIds) {
    return documentRepository.countTopLevelByLibraryIdIn(assetIds).stream()
        .collect(
            Collectors.toMap(
                DocumentRepository.LibraryDocumentCount::getLibraryId,
                DocumentRepository.LibraryDocumentCount::getDocumentCount));
  }
}
