package io.opaa.llm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The model before a confirmed model change, the single row of {@code embedding_model_previous}
 * (ADR-0061, Entscheidung 3): kept from the confirmation until the switch, which deletes it, or the
 * discard, which restores it. The key stays encrypted as it was in {@code embedding_model}.
 */
@Entity
@Table(name = "embedding_model_previous")
class PreviousEmbeddingModel {

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

  @Column(name = "generation", nullable = false)
  private long generation;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "reference_vectors", columnDefinition = "real[]")
  private float[] referenceVectors;

  protected PreviousEmbeddingModel() {}

  /**
   * The values of {@code model}, its key as {@code apiKeyCiphertext} already encrypted for this
   * table's row.
   */
  PreviousEmbeddingModel(EmbeddingModelSettings model, String apiKeyCiphertext) {
    this.id = EmbeddingModelSettings.SINGLETON_ID;
    this.baseUrl = model.getBaseUrl();
    this.modelIdentifier = model.getModelIdentifier();
    this.apiKeyCiphertext = apiKeyCiphertext;
    this.maxInputTokens = model.getMaxInputTokens();
    this.dimensions = model.getDimensions();
    this.installedAt = model.getInstalledAt();
    this.generation = model.getGeneration();
    this.referenceVectors = model.getReferenceVectors();
  }

  String getBaseUrl() {
    return baseUrl;
  }

  String getModelIdentifier() {
    return modelIdentifier;
  }

  String getApiKeyCiphertext() {
    return apiKeyCiphertext;
  }

  int getMaxInputTokens() {
    return maxInputTokens;
  }

  int getDimensions() {
    return dimensions;
  }

  Instant getInstalledAt() {
    return installedAt;
  }

  long getGeneration() {
    return generation;
  }

  float[] getReferenceVectors() {
    return referenceVectors;
  }
}
