package io.opaa.llm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The single row of {@code transcription_model} (ADR-0060, Entscheidung 10), built like {@link
 * RerankModelSettings}; {@link #language} {@code null} lets the model detect the language.
 */
@Entity
@Table(name = "transcription_model")
public class TranscriptionModelSettings {

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

  @Column(name = "language", length = 8)
  private String language;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected TranscriptionModelSettings() {}

  TranscriptionModelSettings(Instant now) {
    this.id = SINGLETON_ID;
    this.updatedAt = now;
  }

  void replace(
      boolean enabled,
      String baseUrl,
      String modelIdentifier,
      String apiKeyCiphertext,
      String language,
      Instant now) {
    this.enabled = enabled;
    this.baseUrl = baseUrl;
    this.modelIdentifier = modelIdentifier;
    this.apiKeyCiphertext = apiKeyCiphertext;
    this.language = language;
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

  public String getLanguage() {
    return language;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
