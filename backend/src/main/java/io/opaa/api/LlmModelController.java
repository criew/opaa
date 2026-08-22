package io.opaa.api;

import io.opaa.api.dto.LlmModelRequest;
import io.opaa.api.dto.LlmModelResponse;
import io.opaa.api.dto.LlmModelTestRequest;
import io.opaa.api.dto.LlmModelTestResponse;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.llm.LlmModel;
import io.opaa.llm.LlmModelConnectionTester;
import io.opaa.llm.LlmModelService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin API for managed chat models (Stufe 1, #757,
 * docs/features/llm-integration.md#stufe-1-verwaltete-chat-modelle-in-umsetzung), {@code
 * SYSTEM_ADMIN} only - the same access bar and {@code currentUser} pattern {@link GroupController}
 * already establishes for admin-only resources.
 *
 * <p><b>The API key is write-only.</b> Every response is a {@link LlmModelResponse}, which never
 * carries an {@code apiKey} field at all (see the OpenAPI schema) - only {@code apiKeySet}, the
 * same convention {@code BrandingResponse} uses for the logo.
 *
 * <p><b>Concurrent activation (#757 review of #763).</b> {@link LlmModelService#activateModel}
 * deactivates the previously active model and flushes before activating the new one, but two
 * concurrent activations of two different models can still collide on {@code
 * ux_llm_models_single_active} (migration 058) when both flush their own "activate" write around
 * the same time. {@link #activateModel} catches that {@link DataIntegrityViolationException} and
 * turns it into a clean 409 rather than letting it surface as an unhandled 500 - the caller is told
 * to retry, not shown a stack trace.
 *
 * <p><b>Delete guard for the active model (#757).</b> {@link LlmModelService#deleteModel}
 * deliberately does not enforce this rule itself (see that method's own Javadoc) - it belongs here,
 * at the request-facing boundary, with a German, actionable message.
 */
@RestController
@RequestMapping("/api/v1/admin/models")
public class LlmModelController {

  private static final String UNKNOWN_ISSUER = "unknown";

  private final LlmModelService llmModelService;
  private final LlmModelConnectionTester connectionTester;
  private final UserService userService;

  public LlmModelController(
      LlmModelService llmModelService,
      LlmModelConnectionTester connectionTester,
      UserService userService) {
    this.llmModelService = llmModelService;
    this.connectionTester = connectionTester;
    this.userService = userService;
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping
  public List<LlmModelResponse> listModels() {
    return llmModelService.listModels().stream().map(LlmModelController::toResponse).toList();
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping
  public ResponseEntity<LlmModelResponse> createModel(
      @Valid @RequestBody LlmModelRequest request, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    LlmModel model =
        llmModelService.createModel(
            currentUser.getOrganizationId(),
            currentUser.getId(),
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
      @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    LlmModel model =
        llmModelService.updateModel(
            currentUser.getOrganizationId(),
            currentUser.getId(),
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
  public ResponseEntity<Void> deleteModel(
      @PathVariable UUID modelId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    LlmModel model = llmModelService.getModel(modelId);
    if (model.isActive()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Das aktive Chat-Modell kann nicht gelöscht werden. Aktivieren Sie zuerst ein anderes"
              + " Modell.");
    }
    llmModelService.deleteModel(currentUser.getOrganizationId(), currentUser.getId(), modelId);
    return ResponseEntity.noContent().build();
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/{modelId}/activate")
  public LlmModelResponse activateModel(
      @PathVariable UUID modelId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    try {
      LlmModel model =
          llmModelService.activateModel(
              currentUser.getOrganizationId(), currentUser.getId(), modelId);
      return toResponse(model);
    } catch (DataIntegrityViolationException e) {
      // #757 review of #763: without this, the concurrency window still ends in a 409 (Spring's
      // built-in DataIntegrityViolationException handler already falls back to CONFLICT), but with
      // a generic "Ein Eintrag mit diesen Werten existiert bereits" that says nothing about what
      // actually happened. This catch trades that generic fallback for a message specific to what
      // a caller can actually do about it: retry the activation.
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Ein anderes Chat-Modell wurde gleichzeitig aktiviert. Bitte erneut versuchen.",
          e);
    }
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/test")
  public LlmModelTestResponse testModel(@Valid @RequestBody LlmModelTestRequest request) {
    LlmModelConnectionTester.TestOutcome outcome =
        connectionTester.test(
            request.getBaseUrl(),
            request.getModelIdentifier(),
            request.getApiKey(),
            request.getModelId());
    return new LlmModelTestResponse(outcome.success(), outcome.message());
  }

  private User currentUser(Jwt jwt) {
    String issuer = jwt.getClaimAsString("iss");
    if (issuer == null || issuer.isBlank()) {
      issuer = UNKNOWN_ISSUER;
    }

    return userService
        .findBySubjectAndIssuer(jwt.getSubject(), issuer)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Benutzer nicht gefunden"));
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
