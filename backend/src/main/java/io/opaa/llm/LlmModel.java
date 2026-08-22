package io.opaa.llm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A managed chat model (Stufe 1, #756,
 * docs/features/llm-integration.md#stufe-1-verwaltete-chat-modelle-in-umsetzung): a row in {@code
 * llm_models} replacing what used to be environment variables only ({@code spring.ai.model.chat}
 * plus the {@code ollama}/{@code openai} blocks in {@code application.yml}).
 *
 * <p><b>Exactly one row may be {@link #active} at a time</b>, enforced by the partial unique index
 * {@code ux_llm_models_single_active} (migration 058) - the database backstop for {@link
 * LlmModelService#activateModel}, which is the primary defense the same way {@code
 * BrandingSettingsService}'s validation is the primary defense for {@code branding_settings}'s own
 * constraints.
 *
 * <p><b>No provider-type column</b>
 * (docs/features/llm-integration.md#ein-anbindungsweg-nicht-zwei): the connection is always the
 * OpenAI-compatible protocol. Ollama is entered via its own {@code /v1} endpoint like any other
 * target; there is deliberately no second, native code path.
 *
 * <p><b>{@link #apiKeyCiphertext} is write-only and encrypted</b> ({@link
 * io.opaa.security.SettingsEncryptor}, AES-256-GCM, {@code OPAA_SETTINGS_ENCRYPTION_KEY}) -
 * optional, because locally operated endpoints regularly run without authentication (#756). It
 * never appears decrypted outside {@link LlmModelService}, and no read path built on top of this
 * entity is expected to return it at all.
 */
@Entity
@Table(name = "llm_models")
public class LlmModel {

  @Id private UUID id;

  @Column(name = "display_name", nullable = false, length = 120)
  private String displayName;

  @Column(name = "base_url", nullable = false, length = 500)
  private String baseUrl;

  @Column(name = "model_identifier", nullable = false, length = 200)
  private String modelIdentifier;

  @Column(name = "temperature", nullable = false, precision = 3, scale = 2)
  private BigDecimal temperature;

  @Column(name = "max_tokens", nullable = false)
  private int maxTokens;

  @Column(name = "api_key_ciphertext")
  private String apiKeyCiphertext;

  @Column(name = "active", nullable = false)
  private boolean active;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected LlmModel() {}

  public LlmModel(
      String displayName,
      String baseUrl,
      String modelIdentifier,
      BigDecimal temperature,
      int maxTokens,
      String apiKeyCiphertext) {
    this.id = UUID.randomUUID();
    this.displayName = displayName;
    this.baseUrl = baseUrl;
    this.modelIdentifier = modelIdentifier;
    this.temperature = temperature;
    this.maxTokens = maxTokens;
    this.apiKeyCiphertext = apiKeyCiphertext;
    this.active = false;
    Instant now = Instant.now();
    this.createdAt = now;
    this.updatedAt = now;
  }

  public UUID getId() {
    return id;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public String getModelIdentifier() {
    return modelIdentifier;
  }

  public BigDecimal getTemperature() {
    return temperature;
  }

  public int getMaxTokens() {
    return maxTokens;
  }

  /** The encrypted access key, or {@code null} if none is configured. Never the plaintext value. */
  public String getApiKeyCiphertext() {
    return apiKeyCiphertext;
  }

  public boolean isActive() {
    return active;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /**
   * Replaces every editable field but {@link #active}, which only {@link #activate}/{@link
   * #deactivate} change. {@code apiKeyCiphertext} is passed through unchanged by the caller when
   * the update leaves the access key untouched (see {@link LlmModelService#updateModel}).
   */
  void replaceDetails(
      String displayName,
      String baseUrl,
      String modelIdentifier,
      BigDecimal temperature,
      int maxTokens,
      String apiKeyCiphertext) {
    this.displayName = displayName;
    this.baseUrl = baseUrl;
    this.modelIdentifier = modelIdentifier;
    this.temperature = temperature;
    this.maxTokens = maxTokens;
    this.apiKeyCiphertext = apiKeyCiphertext;
    this.updatedAt = Instant.now();
  }

  void activate() {
    this.active = true;
    this.updatedAt = Instant.now();
  }

  void deactivate() {
    this.active = false;
    this.updatedAt = Instant.now();
  }
}
