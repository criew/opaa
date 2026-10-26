package io.opaa.llm.web;

import io.opaa.api.dto.RerankModelSettingsResponse;
import io.opaa.api.dto.TranscriptionModelSettingsResponse;
import io.opaa.api.types.ModelRoleCondition;
import io.opaa.llm.ModelRoleAdministration.RerankModelView;
import io.opaa.llm.ModelRoleAdministration.TranscriptionModelView;
import io.opaa.llm.RerankRoleState;
import io.opaa.llm.TranscriptionRoleState;

/** Maps the views of the rerank and transcription roles; never a key. */
final class ModelRoleResponseMapper {

  private ModelRoleResponseMapper() {}

  static RerankModelSettingsResponse toResponse(RerankModelView view) {
    return new RerankModelSettingsResponse(
            view.enabled(),
            view.apiKeySet(),
            condition(view.status().state()),
            view.retiredVariables())
        .baseUrl(view.baseUrl())
        .model(view.model())
        .updatedAt(view.updatedAt());
  }

  static TranscriptionModelSettingsResponse toResponse(TranscriptionModelView view) {
    return new TranscriptionModelSettingsResponse(
            view.enabled(),
            view.apiKeySet(),
            view.language(),
            condition(view.status().state()),
            view.speechInputAvailable(),
            view.retiredVariables())
        .baseUrl(view.baseUrl())
        .model(view.model())
        .updatedAt(view.updatedAt());
  }

  static ModelRoleCondition condition(RerankRoleState state) {
    return switch (state) {
      case DISABLED -> ModelRoleCondition.DISABLED;
      case READY -> ModelRoleCondition.ACTIVE;
      case UNCONFIGURED -> ModelRoleCondition.UNCONFIGURED;
      case UNREACHABLE -> ModelRoleCondition.UNREACHABLE;
    };
  }

  static ModelRoleCondition condition(TranscriptionRoleState state) {
    return switch (state) {
      case DISABLED -> ModelRoleCondition.DISABLED;
      case READY -> ModelRoleCondition.ACTIVE;
      case UNCONFIGURED -> ModelRoleCondition.UNCONFIGURED;
      case UNREACHABLE -> ModelRoleCondition.UNREACHABLE;
    };
  }
}
