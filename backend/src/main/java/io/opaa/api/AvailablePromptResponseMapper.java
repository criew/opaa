package io.opaa.api;

import io.opaa.api.dto.AvailablePrompt;
import java.util.List;

/** Maps the chat's prompt selection onto {@link AvailablePrompt}. */
final class AvailablePromptResponseMapper {

  private AvailablePromptResponseMapper() {}

  static List<AvailablePrompt> toResponses(List<io.opaa.prompt.AvailablePrompt> entries) {
    return entries.stream().map(AvailablePromptResponseMapper::toResponse).toList();
  }

  static AvailablePrompt toResponse(io.opaa.prompt.AvailablePrompt entry) {
    return new AvailablePrompt(
            entry.prompt().getId(),
            entry.library().getId(),
            entry.library().getName(),
            entry.prompt().getName(),
            entry.prompt().getTitle(),
            !entry.prompt().getVariables().isEmpty(),
            entry.associatedWithSpace())
        .description(entry.prompt().getDescription());
  }
}
