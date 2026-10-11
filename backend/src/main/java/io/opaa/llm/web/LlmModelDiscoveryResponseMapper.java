package io.opaa.llm.web;

import io.opaa.api.dto.DiscoveredLlmModel;
import io.opaa.api.dto.LlmModelDiscoveryFailure;
import io.opaa.api.dto.LlmModelDiscoveryResponse;
import io.opaa.llm.DiscoveredModel;
import io.opaa.llm.ModelDiscovery;

/** Maps a {@link ModelDiscovery} onto its generated response DTO; failures map by name. */
final class LlmModelDiscoveryResponseMapper {

  private LlmModelDiscoveryResponseMapper() {}

  static LlmModelDiscoveryResponse toResponse(ModelDiscovery discovery) {
    return new LlmModelDiscoveryResponse(
            discovery.success(),
            discovery.message(),
            discovery.ollama(),
            discovery.models().stream().map(LlmModelDiscoveryResponseMapper::toModel).toList())
        .failure(
            discovery.failure() == null
                ? null
                : LlmModelDiscoveryFailure.valueOf(discovery.failure().name()));
  }

  private static DiscoveredLlmModel toModel(DiscoveredModel model) {
    return new DiscoveredLlmModel(model.identifier(), model.capabilities(), model.chatSuitable())
        .family(model.family())
        .parameterSize(model.parameterSize())
        .quantization(model.quantization())
        .sizeBytes(model.sizeBytes())
        .contextLength(model.contextLength());
  }
}
