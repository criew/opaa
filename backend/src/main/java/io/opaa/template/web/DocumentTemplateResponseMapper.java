package io.opaa.template.web;

import io.opaa.api.dto.AssetReachResponse;
import io.opaa.api.dto.DocumentTemplatePlaceholderKind;
import io.opaa.api.dto.DocumentTemplateResponse;
import io.opaa.api.dto.DocumentTemplateSettingsResponse;
import io.opaa.permission.AssetReach;
import io.opaa.permission.web.SuccessionStateResponseMapper;
import io.opaa.template.DocumentTemplate;
import io.opaa.template.DocumentTemplateSettingsService;
import io.opaa.template.DocumentTemplateView;
import io.opaa.template.TemplatePlaceholder;
import java.util.List;

/** Maps document templates between the API and the domain of {@code io.opaa.template}. */
final class DocumentTemplateResponseMapper {

  private DocumentTemplateResponseMapper() {}

  static DocumentTemplateResponse toResponse(DocumentTemplateView view) {
    DocumentTemplate template = view.template();
    return new DocumentTemplateResponse(
            template.getId(),
            template.getName(),
            template.getOwnerType(),
            template.getOwnerId(),
            toReachResponse(view.reach()),
            view.myRole(),
            template.getFileName(),
            template.getSizeBytes(),
            template.getPlaceholders().stream()
                .map(DocumentTemplateResponseMapper::toPlaceholder)
                .toList(),
            template.getCreatedAt(),
            template.getUpdatedAt())
        .description(template.getDescription())
        .ownerName(view.ownerName())
        .succession(SuccessionStateResponseMapper.toStateResponse(view.succession()));
  }

  static List<DocumentTemplateResponse> toResponses(List<DocumentTemplateView> views) {
    return views.stream().map(DocumentTemplateResponseMapper::toResponse).toList();
  }

  static DocumentTemplateSettingsResponse toSettingsResponse(
      DocumentTemplateSettingsService.View view) {
    return new DocumentTemplateSettingsResponse(view.enabled())
        .updatedAt(view.updatedAt())
        .updatedBy(view.updatedBy());
  }

  private static io.opaa.api.dto.DocumentTemplatePlaceholder toPlaceholder(
      TemplatePlaceholder placeholder) {
    return new io.opaa.api.dto.DocumentTemplatePlaceholder(
        placeholder.name(),
        DocumentTemplatePlaceholderKind.valueOf(placeholder.kind().name()),
        placeholder.fields());
  }

  private static AssetReachResponse toReachResponse(AssetReach reach) {
    return new AssetReachResponse(reach.allAccounts(), reach.groupCount(), reach.userCount());
  }
}
