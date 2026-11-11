package io.opaa.llm.web;

import io.opaa.api.dto.LlmModelTestResponse;
import io.opaa.api.dto.ModelRoleTestRequest;
import io.opaa.api.dto.RerankModelSettingsRequest;
import io.opaa.api.dto.RerankModelSettingsResponse;
import io.opaa.api.dto.TextRecognitionModelSettingsRequest;
import io.opaa.api.dto.TextRecognitionModelSettingsResponse;
import io.opaa.api.dto.TextRecognitionTestRequest;
import io.opaa.api.dto.TextRecognitionTestResponse;
import io.opaa.api.dto.TranscriptionModelSettingsRequest;
import io.opaa.api.dto.TranscriptionModelSettingsResponse;
import io.opaa.api.dto.TranscriptionRealtimeSettingsRequest;
import io.opaa.api.dto.TranscriptionRealtimeTestRequest;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.llm.administration.ModelRoleAdministration;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API of the rerank, transcription and text recognition model roles, {@code SYSTEM_ADMIN}
 * only. Keys are write-only: a response carries {@code apiKeySet}, never the key.
 */
@RestController
@RequestMapping("/api/v1/admin/models")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class ModelRoleController {

  private final ModelRoleAdministration administration;

  public ModelRoleController(ModelRoleAdministration administration) {
    this.administration = administration;
  }

  @GetMapping("/rerank")
  public RerankModelSettingsResponse getRerankModelSettings() {
    return ModelRoleResponseMapper.toResponse(administration.rerank());
  }

  @PutMapping("/rerank")
  public RerankModelSettingsResponse updateRerankModelSettings(
      @Valid @RequestBody RerankModelSettingsRequest request, @Caller CurrentUser caller) {
    return ModelRoleResponseMapper.toResponse(
        administration.changeRerank(
            caller.organizationId(),
            caller.id(),
            Boolean.TRUE.equals(request.getEnabled()),
            request.getBaseUrl(),
            request.getModel(),
            request.getApiKey()));
  }

  @PostMapping("/rerank/test")
  public LlmModelTestResponse testRerankModel(@Valid @RequestBody ModelRoleTestRequest request) {
    ModelRoleAdministration.TestOutcome outcome =
        administration.testRerank(request.getBaseUrl(), request.getModel(), request.getApiKey());
    return new LlmModelTestResponse(outcome.success(), outcome.message());
  }

  @GetMapping("/transcription")
  public TranscriptionModelSettingsResponse getTranscriptionModelSettings() {
    return ModelRoleResponseMapper.toResponse(administration.transcription());
  }

  @PutMapping("/transcription")
  public TranscriptionModelSettingsResponse updateTranscriptionModelSettings(
      @Valid @RequestBody TranscriptionModelSettingsRequest request, @Caller CurrentUser caller) {
    return ModelRoleResponseMapper.toResponse(
        administration.changeTranscription(
            caller.organizationId(),
            caller.id(),
            Boolean.TRUE.equals(request.getEnabled()),
            request.getBaseUrl(),
            request.getModel(),
            request.getApiKey(),
            request.getLanguage(),
            request.getVocabulary(),
            realtimeChange(request.getRealtime())));
  }

  private static ModelRoleAdministration.RealtimeChange realtimeChange(
      TranscriptionRealtimeSettingsRequest realtime) {
    if (realtime == null) {
      return null;
    }
    return new ModelRoleAdministration.RealtimeChange(
        Boolean.TRUE.equals(realtime.getEnabled()),
        realtime.getProtocol(),
        realtime.getUrl(),
        realtime.getModel(),
        realtime.getApiKey(),
        realtime.getMaxSessions());
  }

  @PostMapping("/transcription/realtime/test")
  public LlmModelTestResponse testTranscriptionRealtimeModel(
      @Valid @RequestBody TranscriptionRealtimeTestRequest request) {
    ModelRoleAdministration.TestOutcome outcome =
        administration.testTranscriptionRealtime(
            request.getProtocol(), request.getUrl(), request.getModel(), request.getApiKey());
    return new LlmModelTestResponse(outcome.success(), outcome.message());
  }

  @PostMapping("/transcription/test")
  public LlmModelTestResponse testTranscriptionModel(
      @Valid @RequestBody ModelRoleTestRequest request) {
    ModelRoleAdministration.TestOutcome outcome =
        administration.testTranscription(
            request.getBaseUrl(), request.getModel(), request.getApiKey());
    return new LlmModelTestResponse(outcome.success(), outcome.message());
  }

  @GetMapping("/text-recognition")
  public TextRecognitionModelSettingsResponse getTextRecognitionModelSettings() {
    return ModelRoleResponseMapper.toResponse(administration.textRecognition());
  }

  @PutMapping("/text-recognition")
  public TextRecognitionModelSettingsResponse updateTextRecognitionModelSettings(
      @Valid @RequestBody TextRecognitionModelSettingsRequest request, @Caller CurrentUser caller) {
    return ModelRoleResponseMapper.toResponse(
        administration.changeTextRecognition(
            caller.organizationId(),
            caller.id(),
            Boolean.TRUE.equals(request.getEnabled()),
            request.getConnectionType(),
            request.getBaseUrl(),
            request.getModel(),
            request.getApiKey()));
  }

  @PostMapping("/text-recognition/test")
  public TextRecognitionTestResponse testTextRecognitionModel(
      @Valid @RequestBody TextRecognitionTestRequest request) {
    return ModelRoleResponseMapper.toResponse(
        administration.testTextRecognition(
            request.getConnectionType(),
            request.getBaseUrl(),
            request.getModel(),
            request.getApiKey()));
  }
}
