package io.opaa.llm;

import io.opaa.api.types.ToolCapability;
import io.opaa.api.types.ToolCapabilityBasis;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

/**
 * A managed chat model (ADR-0067): a row in {@code llm_models}, not environment variables.
 *
 * <p><b>Exactly one row may be {@link #active} at a time</b>, enforced by the partial unique index
 * {@code ux_llm_models_single_active} - the database backstop for {@link
 * LlmModelService#activateModel}, which is the primary defense the same way {@code
 * BrandingSettingsService}'s validation is the primary defense for {@code branding_settings}'s own
 * constraints.
 *
 * <p><b>No provider-type column</b>: the connection is always the OpenAI-compatible protocol.
 * Ollama is entered via its own {@code /v1} endpoint like any other target; there is deliberately
 * no second, native code path.
 *
 * <p><b>{@link #apiKeyCiphertext} is write-only and encrypted</b> ({@link
 * io.opaa.security.SettingsEncryptor}, AES-256-GCM, {@code OPAA_SETTINGS_ENCRYPTION_KEY}) -
 * optional, because locally operated endpoints regularly run without authentication. It never
 * appears decrypted outside {@link LlmModelService}, and no read path built on top of this entity
 * is expected to return it at all.
 *
 * <p><b>The tool capability belongs to the combination of endpoint and model identifier</b>: {@link
 * #replaceDetails} resets it to {@link ToolCapability#UNVERIFIED} whenever either changes, and an
 * {@code UNVERIFIED} statement carries no basis, date or measurement run ({@code
 * chk_llm_models_tool_capability_statement}).
 *
 * <p>{@link DynamicUpdate} writes only the changed columns, so a save from one path (activation, a
 * recorded capability) never overwrites a column another transaction changed meanwhile.
 */
@Entity
@DynamicUpdate
@Table(name = "llm_models")
public class LlmModel {

  @Id private UUID id;

  @Column(name = "display_name", nullable = false, length = 120)
  private String displayName;

  @Column(name = "base_url", nullable = false, length = 500)
  private String baseUrl;

  @Column(name = "model_identifier", nullable = false, length = 200)
  private String modelIdentifier;

  /** {@code null}: no temperature is sent to the model. */
  @Column(name = "temperature", precision = 3, scale = 2)
  private BigDecimal temperature;

  @Column(name = "max_tokens", nullable = false)
  private int maxTokens;

  @Column(name = "api_key_ciphertext")
  private String apiKeyCiphertext;

  @Column(name = "active", nullable = false)
  private boolean active;

  @Enumerated(EnumType.STRING)
  @Column(name = "tool_capability", nullable = false, length = 20)
  private ToolCapability toolCapability = ToolCapability.UNVERIFIED;

  /** {@code null} while {@link #toolCapability} is {@link ToolCapability#UNVERIFIED}. */
  @Enumerated(EnumType.STRING)
  @Column(name = "tool_capability_basis", length = 20)
  private ToolCapabilityBasis toolCapabilityBasis;

  @Column(name = "tool_capability_assessed_at")
  private Instant toolCapabilityAssessedAt;

  /** Set only for {@link ToolCapabilityBasis#MEASURED}. */
  @Column(name = "tool_capability_measurement_run", length = 300)
  private String toolCapabilityMeasurementRun;

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
    this(
        UUID.randomUUID(),
        displayName,
        baseUrl,
        modelIdentifier,
        temperature,
        maxTokens,
        apiKeyCiphertext);
  }

  /**
   * A model under {@code id}, for a caller that encrypts the key for this row before it exists
   * ({@link LlmEncryptedColumns#CHAT_MODEL_KEY}).
   */
  public LlmModel(
      UUID id,
      String displayName,
      String baseUrl,
      String modelIdentifier,
      BigDecimal temperature,
      int maxTokens,
      String apiKeyCiphertext) {
    this.id = id;
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

  public ToolCapability getToolCapability() {
    return toolCapability;
  }

  public ToolCapabilityBasis getToolCapabilityBasis() {
    return toolCapabilityBasis;
  }

  public Instant getToolCapabilityAssessedAt() {
    return toolCapabilityAssessedAt;
  }

  public String getToolCapabilityMeasurementRun() {
    return toolCapabilityMeasurementRun;
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
    if (!this.baseUrl.equals(baseUrl) || !this.modelIdentifier.equals(modelIdentifier)) {
      resetToolCapability();
    }
    this.displayName = displayName;
    this.baseUrl = baseUrl;
    this.modelIdentifier = modelIdentifier;
    this.temperature = temperature;
    this.maxTokens = maxTokens;
    this.apiKeyCiphertext = apiKeyCiphertext;
    this.updatedAt = Instant.now();
  }

  /**
   * Records a checked statement; the caller has validated that {@code measurementRun} is set
   * exactly for {@link ToolCapabilityBasis#MEASURED}.
   */
  void assessToolCapability(
      ToolCapability capability,
      ToolCapabilityBasis basis,
      String measurementRun,
      Instant assessedAt) {
    this.toolCapability = capability;
    this.toolCapabilityBasis = basis;
    this.toolCapabilityMeasurementRun = measurementRun;
    this.toolCapabilityAssessedAt = assessedAt;
    this.updatedAt = Instant.now();
  }

  /** Back to {@link ToolCapability#UNVERIFIED}, clearing basis, date and measurement run. */
  void resetToolCapability() {
    this.toolCapability = ToolCapability.UNVERIFIED;
    this.toolCapabilityBasis = null;
    this.toolCapabilityMeasurementRun = null;
    this.toolCapabilityAssessedAt = null;
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
