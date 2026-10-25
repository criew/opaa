package io.opaa.llm;

import io.opaa.compliance.DataFlow;
import io.opaa.instance.InvalidationSubscriber;
import io.opaa.security.SettingsEncryptor;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;

/**
 * The application's {@link EmbeddingModel}: the client of the installed {@link
 * EmbeddingModelSettings}. Every call before an embedding model is installed throws {@link
 * EmbeddingModelNotInstalledException}, so nothing is embedded into a vector table that does not
 * exist yet.
 *
 * <p>The row is written once and not changed afterwards, so the client is cached from the first
 * call that finds it; the absence is never cached. The {@link io.opaa.instance.InvalidationBus}
 * drops the client when the row changes on any instance (ADR-0053, Entscheidung 5).
 */
@DataFlow("embedding-model")
@Component
public class InstalledEmbeddingModel implements EmbeddingModel, InvalidationSubscriber {

  /** The topic the trigger on {@code embedding_model} announces. */
  public static final String INVALIDATION_TOPIC = "embedding-model";

  private static final Logger log = LoggerFactory.getLogger(InstalledEmbeddingModel.class);

  private final EmbeddingModelSettingsRepository repository;
  private final SettingsEncryptor settingsEncryptor;
  private final EmbeddingClientFactory clientFactory;
  private final AtomicReference<Installed> cache = new AtomicReference<>();

  public InstalledEmbeddingModel(
      EmbeddingModelSettingsRepository repository,
      SettingsEncryptor settingsEncryptor,
      EmbeddingClientFactory clientFactory) {
    this.repository = repository;
    this.settingsEncryptor = settingsEncryptor;
    this.clientFactory = clientFactory;
  }

  @Override
  public EmbeddingResponse call(EmbeddingRequest request) {
    return installed().client().call(request);
  }

  @Override
  public float[] embed(Document document) {
    return installed().client().embed(document);
  }

  /** The size the vector table was created with; asks no endpoint. */
  @Override
  public int dimensions() {
    return installed().dimensions();
  }

  /** The input limit of the installed endpoint, in tokens. */
  public int maxInputTokens() {
    return installed().maxInputTokens();
  }

  @Override
  public Set<String> invalidationTopics() {
    return Set.of(INVALIDATION_TOPIC);
  }

  @Override
  public void invalidateAll() {
    cache.set(null);
  }

  private Installed installed() {
    Installed cached = cache.get();
    if (cached != null) {
      return cached;
    }
    return build();
  }

  private synchronized Installed build() {
    Installed cached = cache.get();
    if (cached != null) {
      return cached;
    }
    EmbeddingModelSettings settings =
        repository.findInstalled().orElseThrow(EmbeddingModelNotInstalledException::new);
    EmbeddingEndpoint endpoint =
        new EmbeddingEndpoint(
            settings.getBaseUrl(),
            settings.getModelIdentifier(),
            settingsEncryptor.decrypt(settings.getApiKeyCiphertext()),
            settings.getMaxInputTokens());
    Installed built =
        new Installed(
            clientFactory.create(endpoint), settings.getDimensions(), settings.getMaxInputTokens());
    cache.set(built);
    log.info(
        "Built embedding client for model '{}' at {}",
        settings.getModelIdentifier(),
        ModelEndpointUri.withoutSecrets(settings.getBaseUrl()));
    return built;
  }

  private record Installed(EmbeddingModel client, int dimensions, int maxInputTokens) {}
}
