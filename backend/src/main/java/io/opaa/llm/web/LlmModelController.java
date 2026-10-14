package io.opaa.llm.web;

import io.opaa.api.dto.EmbeddingInfoResponse;
import io.opaa.api.dto.LlmModelDiscoveryRequest;
import io.opaa.api.dto.LlmModelDiscoveryResponse;
import io.opaa.api.dto.LlmModelRequest;
import io.opaa.api.dto.LlmModelResponse;
import io.opaa.api.dto.LlmModelTestRequest;
import io.opaa.api.dto.LlmModelTestResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConflictException;
import io.opaa.llm.EmbeddingInfo;
import io.opaa.llm.EmbeddingInfoService;
import io.opaa.llm.LlmModel;
import io.opaa.llm.LlmModelConnectionTester;
import io.opaa.llm.LlmModelDiscoverer;
import io.opaa.llm.LlmModelService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API for managed chat models, {@code SYSTEM_ADMIN} only. The API key is write-only: a
 * response carries {@code apiKeySet}, never the key. Two concurrent activations colliding on {@code
 * ux_llm_models_single_active} answer 409; {@link LlmModelService#deleteModel} itself rejects
 * deleting the active model, so there is no check-then-act window here.
 */
@RestController
@RequestMapping("/api/v1/admin/models")
public class LlmModelController {

  private final LlmModelService llmModelService;
  private final LlmModelConnectionTester connectionTester;
  private final EmbeddingInfoService embeddingInfoService;
  private final LlmModelDiscoverer discoverer;

  public LlmModelController(
      LlmModelService llmModelService,
      LlmModelConnectionTester connectionTester,
      EmbeddingInfoService embeddingInfoService,
      LlmModelDiscoverer discoverer) {
    this.llmModelService = llmModelService;
    this.connectionTester = connectionTester;
    this.embeddingInfoService = embeddingInfoService;
    this.discoverer = discoverer;
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping
  public List<LlmModelResponse> listModels() {
    return llmModelService.listModels().stream().map(LlmModelController::toResponse).toList();
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping
  public ResponseEntity<LlmModelResponse> createModel(
      @Valid @RequestBody LlmModelRequest request, @Caller CurrentUser caller) {
    LlmModel model =
        llmModelService.createModel(
            caller.organizationId(),
            caller.id(),
            request.getDisplayName(),
            request.getBaseUrl(),
            request.getModelIdentifier(),
            toTemperature(request.getTemperature()),
            request.getMaxTokens(),
            request.getApiKey());
    return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(model));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PutMapping("/{modelId}")
  public LlmModelResponse updateModel(
      @PathVariable UUID modelId,
      @Valid @RequestBody LlmModelRequest request,
      @Caller CurrentUser caller) {
    LlmModel model =
        llmModelService.updateModel(
            caller.organizationId(),
            caller.id(),
            modelId,
            request.getDisplayName(),
            request.getBaseUrl(),
            request.getModelIdentifier(),
            toTemperature(request.getTemperature()),
            request.getMaxTokens(),
            request.getApiKey());
    return toResponse(model);
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @DeleteMapping("/{modelId}")
  public ResponseEntity<Void> deleteModel(@PathVariable UUID modelId, @Caller CurrentUser caller) {
    llmModelService.deleteModel(caller.organizationId(), caller.id(), modelId);
    return ResponseEntity.noContent().build();
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/{modelId}/activate")
  public LlmModelResponse activateModel(@PathVariable UUID modelId, @Caller CurrentUser caller) {
    try {
      LlmModel model = llmModelService.activateModel(caller.organizationId(), caller.id(), modelId);
      return toResponse(model);
    } catch (DataIntegrityViolationException e) {
      // A concurrent activation; the specific message tells the caller to retry, unlike the
      // generic 409 of the global handler.
      throw new ConflictException(
          "Ein anderes Chat-Modell wurde gleichzeitig aktiviert. Bitte erneut versuchen.", e);
    }
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/embedding-info")
  public EmbeddingInfoResponse getEmbeddingInfo() {
    EmbeddingInfo info = embeddingInfoService.getEmbeddingInfo();
    return new EmbeddingInfoResponse(info.provider(), info.model(), info.dimensions());
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/test")
  public LlmModelTestResponse testModel(@Valid @RequestBody LlmModelTestRequest request) {
    LlmModelConnectionTester.TestOutcome outcome =
        connectionTester.test(
            request.getBaseUrl(),
            request.getModelIdentifier(),
            request.getApiKey(),
            request.getModelId(),
            toTemperature(request.getTemperature()));
    return new LlmModelTestResponse(outcome.success(), outcome.message());
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/discover")
  public LlmModelDiscoveryResponse discoverModels(
      @Valid @RequestBody LlmModelDiscoveryRequest request) {
    return LlmModelDiscoveryResponseMapper.toResponse(
        discoverer.discover(request.getBaseUrl(), request.getApiKey(), request.getModelId()));
  }

  private static BigDecimal toTemperature(Double temperature) {
    return temperature == null ? null : BigDecimal.valueOf(temperature);
  }

  private static LlmModelResponse toResponse(LlmModel model) {
    return new LlmModelResponse(
        model.getId(),
        model.getDisplayName(),
        model.getBaseUrl(),
        model.getModelIdentifier(),
        model.getTemperature() == null ? null : model.getTemperature().doubleValue(),
        model.getMaxTokens(),
        model.getApiKeyCiphertext() != null,
        model.isActive(),
        model.getCreatedAt(),
        model.getUpdatedAt());
  }
}
