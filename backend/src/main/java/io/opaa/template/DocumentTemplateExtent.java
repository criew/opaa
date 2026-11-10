package io.opaa.template;

import io.opaa.asset.AssetExtent;
import io.opaa.permission.AssetType;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** A document template's extent: its placeholders. */
@Component
class DocumentTemplateExtent implements AssetExtent {

  private final DocumentTemplateRepository repository;

  DocumentTemplateExtent(DocumentTemplateRepository repository) {
    this.repository = repository;
  }

  @Override
  public AssetType assetType() {
    return DocumentTemplate.ASSET_TYPE;
  }

  @Override
  public Map<UUID, Long> itemCounts(Collection<UUID> assetIds) {
    if (assetIds.isEmpty()) {
      return Map.of();
    }
    return repository.countPlaceholders(assetIds).stream()
        .collect(
            Collectors.toMap(
                count -> UUID.fromString(count.getTemplateId()),
                DocumentTemplateRepository.PlaceholderCount::getCount));
  }
}
