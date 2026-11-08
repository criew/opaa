package io.opaa.llm;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;

/**
 * The columns every optional model role stores in its single row (ADR-0060, Entscheidung 10),
 * written only by {@link ModelRoleAdministration}. Without the row the factory default applies. The
 * key is encrypted with {@code SettingsEncryptor} and leaves {@link ModelRoleEndpoints} decrypted
 * only for the client.
 */
@MappedSuperclass
public abstract class OptionalRoleSettings {

  static final short SINGLETON_ID = 1;

  @Id private short id;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @Column(name = "base_url", length = 500)
  private String baseUrl;

  @Column(name = "model_identifier", length = 200)
  private String modelIdentifier;

  @Column(name = "api_key_ciphertext")
  private String apiKeyCiphertext;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected OptionalRoleSettings() {}

  protected OptionalRoleSettings(Instant now) {
    this.id = SINGLETON_ID;
    this.updatedAt = now;
  }

  /** Replaces the columns of every role; a role replaces its own columns alongside. */
  void replaceEndpoint(
      boolean enabled,
      String baseUrl,
      String modelIdentifier,
      String apiKeyCiphertext,
      Instant now) {
    this.enabled = enabled;
    this.baseUrl = baseUrl;
    this.modelIdentifier = modelIdentifier;
    this.apiKeyCiphertext = apiKeyCiphertext;
    this.updatedAt = now;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public String getModelIdentifier() {
    return modelIdentifier;
  }

  String getApiKeyCiphertext() {
    return apiKeyCiphertext;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
