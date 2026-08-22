package io.opaa.llm;

import io.opaa.audit.AuditEventRecorder;
import io.opaa.audit.AuditEventType;
import io.opaa.audit.AuditObjectType;
import io.opaa.audit.AuditOutcome;
import io.opaa.security.SettingsEncryptor;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reads and changes the managed chat models (Stufe 1, #756,
 * docs/features/llm-integration.md#stufe-1-verwaltete-chat-modelle-in-umsetzung) - the persistence
 * and audit layer {@code io.opaa.branding.BrandingSettingsService} is modelled after, adapted to a
 * real list of rows instead of a singleton.
 *
 * <p><b>The API key is write-only.</b> {@link #createModel} and {@link #updateModel} accept it as
 * plaintext and store only {@link SettingsEncryptor#encrypt} of it; nothing in this class ever
 * returns a decrypted key, and {@link LlmModel#getApiKeyCiphertext()} is the encrypted form only -
 * decrypting it is reserved for the future runtime call path (#758) that actually talks to the
 * endpoint.
 *
 * <p>Every change records an audit event ({@link AuditEventType#LLM_MODEL_CREATED}/{@link
 * AuditEventType#LLM_MODEL_CHANGED}/{@link AuditEventType#LLM_MODEL_DELETED}/{@link
 * AuditEventType#LLM_MODEL_ACTIVATED}/{@link AuditEventType#LLM_MODEL_DEACTIVATED}) with {@code
 * before}/{@code after} maps that never carry the key itself - only whether one is set, the same
 * convention {@code BrandingSettingsService} uses for the logo's bytes.
 */
@Service
public class LlmModelService {

  private static final String OBJECT_LABEL_PREFIX = "Chat-Modell";

  private final LlmModelRepository repository;
  private final SettingsEncryptor settingsEncryptor;
  private final AuditEventRecorder auditEventRecorder;

  public LlmModelService(
      LlmModelRepository repository,
      SettingsEncryptor settingsEncryptor,
      AuditEventRecorder auditEventRecorder) {
    this.repository = repository;
    this.settingsEncryptor = settingsEncryptor;
    this.auditEventRecorder = auditEventRecorder;
  }

  @Transactional(readOnly = true)
  public List<LlmModel> listModels() {
    return repository.findAllByOrderByDisplayNameAsc();
  }

  @Transactional(readOnly = true)
  public LlmModel getModel(UUID id) {
    return repository.findById(id).orElseThrow(() -> notFound(id));
  }

  /**
   * Creates a new model, inactive by default - {@link #activateModel} is the only way a model
   * becomes the systemwide active one, so creating and activating are always two distinguishable
   * audit events even when a caller does both in the same request.
   */
  @Transactional
  public LlmModel createModel(
      UUID organizationId,
      UUID actorUserId,
      String displayName,
      String baseUrl,
      String modelIdentifier,
      BigDecimal temperature,
      int maxTokens,
      String apiKey) {
    LlmModel model =
        new LlmModel(
            displayName,
            baseUrl,
            modelIdentifier,
            temperature,
            maxTokens,
            settingsEncryptor.encrypt(apiKey));
    repository.save(model);
    recordChange(
        organizationId,
        actorUserId,
        AuditEventType.LLM_MODEL_CREATED,
        model,
        null,
        auditState(model));
    return model;
  }

  /**
   * Replaces every editable field. {@code apiKey} follows the same three-way convention {@code
   * BrandingSettingsService#updateBranding} uses for its own optional fields: {@code null} leaves
   * the stored key unchanged, a blank string clears it, and any other value replaces it.
   */
  @Transactional
  public LlmModel updateModel(
      UUID organizationId,
      UUID actorUserId,
      UUID id,
      String displayName,
      String baseUrl,
      String modelIdentifier,
      BigDecimal temperature,
      int maxTokens,
      String apiKey) {
    LlmModel model = repository.findById(id).orElseThrow(() -> notFound(id));
    Map<String, Object> before = auditState(model);
    String apiKeyCiphertext =
        apiKey == null ? model.getApiKeyCiphertext() : settingsEncryptor.encrypt(apiKey);
    model.replaceDetails(
        displayName, baseUrl, modelIdentifier, temperature, maxTokens, apiKeyCiphertext);
    repository.save(model);
    recordChange(
        organizationId,
        actorUserId,
        AuditEventType.LLM_MODEL_CHANGED,
        model,
        before,
        auditState(model));
    return model;
  }

  /**
   * Deletes a model - rejected with 409 while it is the systemwide active one (#757 review: the
   * check and the delete must be the same transaction, not a {@code getModel}/{@code deleteModel}
   * pair the controller composes itself, which left a TOCTOU window between "still active" and
   * "gone" open to a concurrent {@link #activateModel}/{@link #updateModel} call).
   */
  @Transactional
  public void deleteModel(UUID organizationId, UUID actorUserId, UUID id) {
    LlmModel model = repository.findById(id).orElseThrow(() -> notFound(id));
    if (model.isActive()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Das aktive Chat-Modell kann nicht gelöscht werden. Aktivieren Sie zuerst ein anderes"
              + " Modell.");
    }
    Map<String, Object> before = auditState(model);
    repository.delete(model);
    auditEventRecorder.recordUserAction(
        organizationId,
        actorUserId,
        AuditEventType.LLM_MODEL_DELETED,
        AuditObjectType.SYSTEM_SETTING,
        model.getId(),
        objectLabel(model),
        before,
        null,
        AuditOutcome.SUCCESS,
        null);
  }

  /**
   * Makes {@code id} the one systemwide active model, deactivating whatever was active before -
   * {@link LlmModelRepository#findAllByActiveTrue()} rather than assuming there was at most one,
   * since this method is exactly what is supposed to keep that true and must not silently trust it.
   *
   * <p>The deactivation is flushed before the new model is activated (rather than left to
   * commit-time flush ordering): {@code ux_llm_models_single_active} (migration 058) is a plain,
   * non-deferrable unique index, so if Hibernate's flush happened to write the new {@code active =
   * true} row before the old one's {@code active = false}, the two would collide even though the
   * end state is exactly one active row - a transient violation of an invariant that never actually
   * held.
   */
  @Transactional
  public LlmModel activateModel(UUID organizationId, UUID actorUserId, UUID id) {
    LlmModel model = repository.findById(id).orElseThrow(() -> notFound(id));
    if (model.isActive()) {
      return model;
    }
    for (LlmModel currentlyActive : repository.findAllByActiveTrue()) {
      currentlyActive.deactivate();
      repository.saveAndFlush(currentlyActive);
      // #757 review of #763: the model that stops being active gets its own audit event, distinct
      // from the LLM_MODEL_ACTIVATED event of whatever model replaces it - otherwise "wann hörte
      // Modell X auf, aktiv zu sein" was only indirectly readable.
      recordChange(
          organizationId,
          actorUserId,
          AuditEventType.LLM_MODEL_DEACTIVATED,
          currentlyActive,
          Map.of("active", true),
          Map.of("active", false));
    }
    model.activate();
    repository.save(model);
    recordChange(
        organizationId,
        actorUserId,
        AuditEventType.LLM_MODEL_ACTIVATED,
        model,
        Map.of("active", false),
        Map.of("active", true));
    return model;
  }

  private void recordChange(
      UUID organizationId,
      UUID actorUserId,
      AuditEventType eventType,
      LlmModel model,
      Map<String, Object> before,
      Map<String, Object> after) {
    auditEventRecorder.recordUserAction(
        organizationId,
        actorUserId,
        eventType,
        AuditObjectType.SYSTEM_SETTING,
        model.getId(),
        objectLabel(model),
        before,
        after,
        AuditOutcome.SUCCESS,
        null);
  }

  /**
   * Never the key itself - only whether one is set, mirroring the branding logo's own convention.
   */
  private Map<String, Object> auditState(LlmModel model) {
    return Map.of(
        "displayName", model.getDisplayName(),
        "baseUrl", model.getBaseUrl(),
        "modelIdentifier", model.getModelIdentifier(),
        "temperature", model.getTemperature(),
        "maxTokens", model.getMaxTokens(),
        "apiKeySet", model.getApiKeyCiphertext() != null,
        "active", model.isActive());
  }

  private String objectLabel(LlmModel model) {
    return OBJECT_LABEL_PREFIX + ": " + model.getDisplayName();
  }

  private static ResponseStatusException notFound(UUID id) {
    return new ResponseStatusException(
        HttpStatus.NOT_FOUND, "Kein Chat-Modell mit der ID " + id + " gefunden");
  }
}
