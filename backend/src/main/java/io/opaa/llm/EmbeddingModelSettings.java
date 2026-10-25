package io.opaa.llm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The embedding model of the installation (ADR-0060, Entscheidung 10): the single row of {@code
 * embedding_model}, written once and not changed through the administration, because the vectors of
 * every chunk depend on it. {@link #dimensions} is the size the vector table was created with. The
 * key is encrypted with {@code SettingsEncryptor} and never leaves {@link EmbeddingModelService}
 * and {@link InstalledEmbeddingModel} decrypted.
 */
@Entity
@Table(name = "embedding_model")
public class EmbeddingModelSettings {

  static final short SINGLETON_ID = 1;

  @Id private short id;

  @Column(name = "base_url", nullable = false, length = 500)
  private String baseUrl;

  @Column(name = "model_identifier", nullable = false, length = 200)
  private String modelIdentifier;

  @Column(name = "api_key_ciphertext")
  private String apiKeyCiphertext;

  @Column(name = "max_input_tokens", nullable = false)
  private int maxInputTokens;

  @Column(name = "dimensions", nullable = false)
  private int dimensions;

  @Column(name = "installed_at", nullable = false)
  private Instant installedAt;

  protected EmbeddingModelSettings() {}

  EmbeddingModelSettings(
      String baseUrl,
      String modelIdentifier,
      String apiKeyCiphertext,
      int maxInputTokens,
      int dimensions,
      Instant installedAt) {
    this.id = SINGLETON_ID;
    this.baseUrl = baseUrl;
    this.modelIdentifier = modelIdentifier;
    this.apiKeyCiphertext = apiKeyCiphertext;
    this.maxInputTokens = maxInputTokens;
    this.dimensions = dimensions;
    this.installedAt = installedAt;
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

  public boolean hasApiKey() {
    return apiKeyCiphertext != null;
  }

  public int getMaxInputTokens() {
    return maxInputTokens;
  }

  public int getDimensions() {
    return dimensions;
  }

  public Instant getInstalledAt() {
    return installedAt;
  }
}
