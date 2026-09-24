package io.opaa.api;

import io.opaa.api.dto.PromptLibraryRequest;
import io.opaa.api.dto.PromptLibraryResponse;
import io.opaa.api.dto.PromptLibraryUpdateRequest;
import io.opaa.api.dto.PromptRequest;
import io.opaa.api.dto.PromptResponse;
import io.opaa.prompt.Prompt;
import io.opaa.prompt.PromptContent;
import io.opaa.prompt.PromptLibrary;
import io.opaa.prompt.PromptLibraryCreation;
import io.opaa.prompt.PromptLibraryUpdate;
import io.opaa.prompt.PromptLibraryView;
import io.opaa.prompt.PromptVariable;
import java.util.List;

/** Maps prompt libraries and prompts between the API and the domain of {@code io.opaa.prompt}. */
final class PromptLibraryResponseMapper {

  private PromptLibraryResponseMapper() {}

  static PromptLibraryCreation toCreation(PromptLibraryRequest request) {
    return new PromptLibraryCreation(
        request.getName(),
        request.getDescription(),
        request.getOwnerType(),
        request.getOwnerId(),
        request.getVisibility(),
        request.getListed());
  }

  static PromptLibraryUpdate toUpdate(PromptLibraryUpdateRequest request) {
    return new PromptLibraryUpdate(
        request.getName(),
        request.getDescription(),
        request.getVisibility(),
        Boolean.TRUE.equals(request.getListed()));
  }

  static PromptLibraryResponse toResponse(PromptLibraryView view) {
    PromptLibrary library = view.library();
    return new PromptLibraryResponse(
            library.getId(),
            library.getName(),
            library.getOwnerType(),
            library.getOwnerId(),
            library.getVisibility(),
            library.isListed(),
            view.myRole(),
            view.promptCount(),
            library.getCreatedAt(),
            library.getUpdatedAt())
        .description(library.getDescription())
        .ownerName(view.ownerName())
        .succession(SuccessionResponseMapper.toStateResponse(view.succession()));
  }

  static List<PromptLibraryResponse> toResponses(List<PromptLibraryView> views) {
    return views.stream().map(PromptLibraryResponseMapper::toResponse).toList();
  }

  static PromptContent toContent(PromptRequest request) {
    List<PromptVariable> variables =
        request.getVariables() == null
            ? List.of()
            : request.getVariables().stream().map(PromptLibraryResponseMapper::toVariable).toList();
    return new PromptContent(
        request.getName(),
        request.getTitle(),
        request.getDescription(),
        request.getText(),
        variables,
        request.getSortOrder() == null ? 0 : request.getSortOrder());
  }

  static PromptResponse toResponse(Prompt prompt) {
    return new PromptResponse(
            prompt.getId(),
            prompt.getLibraryId(),
            prompt.getName(),
            prompt.getTitle(),
            prompt.getText(),
            prompt.getVariables().stream().map(PromptLibraryResponseMapper::toDto).toList(),
            prompt.getSortOrder(),
            prompt.getCreatedAt(),
            prompt.getUpdatedAt())
        .description(prompt.getDescription());
  }

  static List<PromptResponse> toPromptResponses(List<Prompt> prompts) {
    return prompts.stream().map(PromptLibraryResponseMapper::toResponse).toList();
  }

  private static PromptVariable toVariable(io.opaa.api.dto.PromptVariable dto) {
    if (dto == null) {
      return null;
    }
    return new PromptVariable(
        dto.getName(),
        dto.getLabel(),
        dto.getType(),
        Boolean.TRUE.equals(dto.getRequired()),
        dto.getDefaultValue(),
        dto.getOptions());
  }

  private static io.opaa.api.dto.PromptVariable toDto(PromptVariable variable) {
    return new io.opaa.api.dto.PromptVariable(
            variable.name(), variable.label(), variable.type(), variable.required())
        .defaultValue(variable.defaultValue())
        .options(variable.options().isEmpty() ? null : variable.options());
  }
}
