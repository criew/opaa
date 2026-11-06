package io.opaa.llm;

import io.opaa.api.types.TextRecognitionConnectionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The single row of {@code text_recognition_model}, built like {@link TranscriptionModelSettings};
 * {@link #modelIdentifier} is {@code null} for {@link TextRecognitionConnectionType#DOCLING_SERVE}.
 */
@Entity
@Table(name = "text_recognition_model")
public class TextRecognitionModelSettings {

  static final short SINGLETON_ID = 1;

  @Id private short id;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @Enumerated(EnumType.STRING)
  @Column(name = "connection_type", nullable = false, length = 32)
  private TextRecognitionConnectionType connectionType;

  @Column(name = "base_url", length = 500)
  private String baseUrl;

  @Column(name = "model_identifier", length = 200)
  private String modelIdentifier;

  @Column(name = "api_key_ciphertext")
  private String apiKeyCiphertext;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected TextRecognitionModelSettings() {}

  TextRecognitionModelSettings(Instant now) {
    this.id = SINGLETON_ID;
    this.connectionType = TextRecognitionEndpoint.DEFAULT_CONNECTION_TYPE;
    this.updatedAt = now;
  }

  void replace(
      boolean enabled,
      TextRecognitionConnectionType connectionType,
      String baseUrl,
      String modelIdentifier,
      String apiKeyCiphertext,
      Instant now) {
    this.enabled = enabled;
    this.connectionType = connectionType;
    this.baseUrl = baseUrl;
    this.modelIdentifier = modelIdentifier;
    this.apiKeyCiphertext = apiKeyCiphertext;
    this.updatedAt = now;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public TextRecognitionConnectionType getConnectionType() {
    return connectionType;
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
