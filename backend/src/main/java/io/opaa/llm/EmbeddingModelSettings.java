package io.opaa.llm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The embedding model of the installation (ADR-0060, Entscheidung 10): the single row of {@code
 * embedding_model}. {@link #getDimensions()} is the size of the vector column of {@link
 * #getGeneration()}; a model change gives the row a new generation, an endpoint change keeps it
 * (ADR-0061, Entscheidungen 2 and 5). The key is encrypted with {@code SettingsEncryptor} and never
 * leaves {@link EmbeddingModelService} and {@link InstalledEmbeddingModel} decrypted.
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

  @Column(name = "generation", nullable = false)
  private long generation;

  /**
   * The vectors of {@link EmbeddingAnchor#TEXTS}, one after the other; {@code null} until taken.
   */
  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "reference_vectors", columnDefinition = "real[]")
  private float[] referenceVectors;

  protected EmbeddingModelSettings() {}

  EmbeddingModelSettings(
      String baseUrl,
      String modelIdentifier,
      String apiKeyCiphertext,
      int maxInputTokens,
      int dimensions,
      Instant installedAt,
      long generation,
      float[] referenceVectors) {
    this.id = SINGLETON_ID;
    this.baseUrl = baseUrl;
    this.modelIdentifier = modelIdentifier;
    this.apiKeyCiphertext = apiKeyCiphertext;
    this.maxInputTokens = maxInputTokens;
    this.dimensions = dimensions;
    this.installedAt = installedAt;
    this.generation = generation;
    this.referenceVectors = referenceVectors;
  }

  /**
   * Takes over every value of {@code previous}, the model a discarded change goes back to; its key
   * as {@code apiKeyCiphertext}, already encrypted for this table's row.
   */
  void restore(PreviousEmbeddingModel previous, String apiKeyCiphertext) {
    this.baseUrl = previous.getBaseUrl();
    this.modelIdentifier = previous.getModelIdentifier();
    this.apiKeyCiphertext = apiKeyCiphertext;
    this.maxInputTokens = previous.getMaxInputTokens();
    this.dimensions = previous.getDimensions();
    this.installedAt = previous.getInstalledAt();
    this.generation = previous.getGeneration();
    this.referenceVectors = previous.getReferenceVectors();
  }

  /** Becomes another model under a new generation, with the anchor of its probe. */
  void replace(
      EmbeddingEndpoint endpoint,
      String newApiKeyCiphertext,
      int newDimensions,
      Instant at,
      long newGeneration,
      float[] anchor) {
    this.baseUrl = endpoint.baseUrl();
    this.modelIdentifier = endpoint.modelIdentifier();
    this.apiKeyCiphertext = newApiKeyCiphertext;
    this.maxInputTokens = endpoint.maxInputTokens();
    this.dimensions = newDimensions;
    this.installedAt = at;
    this.generation = newGeneration;
    this.referenceVectors = anchor;
  }

  /** Another endpoint of the same model: generation, dimension and anchor stay. */
  void moveEndpoint(String newBaseUrl, String newApiKeyCiphertext, int newMaxInputTokens) {
    this.baseUrl = newBaseUrl;
    this.apiKeyCiphertext = newApiKeyCiphertext;
    this.maxInputTokens = newMaxInputTokens;
  }

  /** Gives the row a generation no column carries, after a change outside the guided way. */
  void renumber(long newGeneration) {
    this.generation = newGeneration;
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

  public long getGeneration() {
    return generation;
  }

  float[] getReferenceVectors() {
    return referenceVectors;
  }

  public boolean hasReferenceAnchor() {
    return referenceVectors != null;
  }

  /**
   * A digest of everything a client is built from - generation, address, model, key ciphertext,
   * input limit and size: equal digests mean the same client. Never reveals the key.
   */
  public String fingerprint() {
    String text =
        String.join(
            "\u0000",
            Long.toString(generation),
            String.valueOf(baseUrl),
            String.valueOf(modelIdentifier),
            String.valueOf(apiKeyCiphertext),
            Integer.toString(maxInputTokens),
            Integer.toString(dimensions));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
