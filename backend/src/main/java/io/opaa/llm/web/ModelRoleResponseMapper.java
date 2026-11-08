package io.opaa.llm.web;

import io.opaa.api.dto.RerankModelSettingsResponse;
import io.opaa.api.dto.TextRecognitionModelSettingsResponse;
import io.opaa.api.dto.TextRecognitionTestResponse;
import io.opaa.api.dto.TranscriptionModelSettingsResponse;
import io.opaa.api.types.ModelRoleCondition;
import io.opaa.llm.ModelRoleAdministration.RerankModelView;
import io.opaa.llm.ModelRoleAdministration.TextRecognitionModelView;
import io.opaa.llm.ModelRoleAdministration.TextRecognitionTestOutcome;
import io.opaa.llm.ModelRoleAdministration.TranscriptionModelView;
import io.opaa.llm.OptionalRoleState;
import io.opaa.llm.TextRecognitionClient;

/** Maps the views of the rerank, transcription and text recognition roles; never a key. */
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
            view.vocabulary(),
            condition(view.status().state()),
            view.speechInputAvailable(),
            view.retiredVariables())
        .baseUrl(view.baseUrl())
        .model(view.model())
        .updatedAt(view.updatedAt());
  }

  static TextRecognitionModelSettingsResponse toResponse(TextRecognitionModelView view) {
    return new TextRecognitionModelSettingsResponse(
            view.enabled(),
            view.connectionType(),
            view.apiKeySet(),
            condition(view.status().state()),
            view.textRecognitionAvailable())
        .baseUrl(view.baseUrl())
        .model(view.model())
        .updatedAt(view.updatedAt());
  }

  static TextRecognitionTestResponse toResponse(TextRecognitionTestOutcome outcome) {
    return new TextRecognitionTestResponse(
            outcome.success(),
            switch (outcome.outcome()) {
              case READY -> TextRecognitionTestResponse.OutcomeEnum.READY;
              case KEY_REJECTED -> TextRecognitionTestResponse.OutcomeEnum.KEY_REJECTED;
              case ENGINE_MISSING -> TextRecognitionTestResponse.OutcomeEnum.ENGINE_MISSING;
              case NO_TEXT -> TextRecognitionTestResponse.OutcomeEnum.NO_TEXT;
              case UNREACHABLE -> TextRecognitionTestResponse.OutcomeEnum.UNREACHABLE;
            },
            outcome.message())
        .failedStage(failedStage(outcome.failedStage()));
  }

  private static TextRecognitionTestResponse.FailedStageEnum failedStage(
      TextRecognitionClient.FailedStage stage) {
    if (stage == null) {
      return null;
    }
    return switch (stage) {
      case ADDRESS -> TextRecognitionTestResponse.FailedStageEnum.ADDRESS;
      case AUTHENTICATION -> TextRecognitionTestResponse.FailedStageEnum.AUTHENTICATION;
      case RECOGNITION -> TextRecognitionTestResponse.FailedStageEnum.RECOGNITION;
    };
  }

  static ModelRoleCondition condition(OptionalRoleState state) {
    return switch (state) {
      case DISABLED -> ModelRoleCondition.DISABLED;
      case READY -> ModelRoleCondition.ACTIVE;
      case UNCONFIGURED -> ModelRoleCondition.UNCONFIGURED;
      case UNREACHABLE -> ModelRoleCondition.UNREACHABLE;
    };
  }
}
