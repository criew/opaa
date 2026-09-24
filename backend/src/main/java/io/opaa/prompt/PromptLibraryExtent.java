package io.opaa.prompt;

import io.opaa.asset.AssetExtent;
import io.opaa.permission.AssetType;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** A prompt library's extent: its prompts. */
@Component
class PromptLibraryExtent implements AssetExtent {

  private final PromptRepository promptRepository;

  PromptLibraryExtent(PromptRepository promptRepository) {
    this.promptRepository = promptRepository;
  }

  @Override
  public AssetType assetType() {
    return PromptLibrary.ASSET_TYPE;
  }

  @Override
  public Map<UUID, Long> itemCounts(Collection<UUID> assetIds) {
    return promptRepository.countByLibraryIdIn(assetIds).stream()
        .collect(
            Collectors.toMap(
                PromptRepository.LibraryPromptCount::getLibraryId,
                PromptRepository.LibraryPromptCount::getPromptCount));
  }
}
