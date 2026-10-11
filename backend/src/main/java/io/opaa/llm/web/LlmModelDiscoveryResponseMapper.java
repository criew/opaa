package io.opaa.llm.web;

import io.opaa.api.dto.LlmModelDiscoveryResponse;
import io.opaa.llm.ModelDiscovery;

/** Maps a {@link ModelDiscovery} onto its generated response DTO. */
final class LlmModelDiscoveryResponseMapper {

  private LlmModelDiscoveryResponseMapper() {}

  static LlmModelDiscoveryResponse toResponse(ModelDiscovery discovery) {
    throw new UnsupportedOperationException("not implemented yet");
  }
}
