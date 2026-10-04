package io.opaa.library.web;

import io.opaa.api.dto.CatalogEntryResponse;
import io.opaa.api.dto.CatalogKnowledgeLibraryFacts;
import io.opaa.asset.AssetCatalogFacts;
import io.opaa.asset.web.CatalogFactsResponseMapper;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryCatalogFacts;
import io.opaa.permission.AssetType;
import org.springframework.stereotype.Component;

/**
 * Carries a knowledge library's catalog facts into {@code CatalogEntryResponse.knowledgeLibrary}.
 */
@Component
class KnowledgeLibraryCatalogFactsMapper implements CatalogFactsResponseMapper {

  @Override
  public AssetType assetType() {
    return KnowledgeLibrary.ASSET_TYPE;
  }

  @Override
  public void apply(AssetCatalogFacts facts, CatalogEntryResponse response) {
    KnowledgeLibraryCatalogFacts knowledge = (KnowledgeLibraryCatalogFacts) facts;
    response.knowledgeLibrary(
        new CatalogKnowledgeLibraryFacts()
            .sourceType(knowledge.sourceType())
            .indexingStatus(knowledge.status())
            .lastIndexedAt(knowledge.lastIndexedAt())
            .sourceLockNotice(knowledge.sourceLockNotice()));
  }
}
