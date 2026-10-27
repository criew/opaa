package io.opaa.llm;

import io.opaa.compliance.DataFlow;
import io.opaa.instance.InvalidationSubscriber;
import io.opaa.security.SettingsEncryptor;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 * <p>A completed installation of the {@code dev} mode without a model - its self-installation at
 * the start found the endpoint unreachable - tries it again on use, at most once per backoff
 * interval of this instance, doubling from {@link #FIRST_RETRY} to {@link #MAX_RETRY} (ADR-0053:
 * "je Instanz"; the installation itself runs under the schema lock). Every other mode installs
 * nothing here.
 */
@DataFlow("embedding-model")
@Component
public class InstalledEmbeddingModel implements EmbeddingModel, InvalidationSubscriber {

  /** The topic the trigger on {@code embedding_model} announces. */
  public static final String INVALIDATION_TOPIC = "embedding-model";

  static final Duration FIRST_RETRY = Duration.ofSeconds(30);
  static final Duration MAX_RETRY = Duration.ofMinutes(10);

  private static final Logger log = LoggerFactory.getLogger(InstalledEmbeddingModel.class);

  private final EmbeddingModelSettingsRepository repository;
  private final SettingsEncryptor settingsEncryptor;
  private final EmbeddingClientFactory clientFactory;
  private final EmbeddingModelService embeddingModels;
  private final LongSupplier nanoTime;
  private final AtomicReference<Installed> cache = new AtomicReference<>();
  private final AtomicBoolean takeoverRunning = new AtomicBoolean();
  private volatile long nextTakeover;
  private volatile Duration retry = FIRST_RETRY;

  @Autowired
  public InstalledEmbeddingModel(
      EmbeddingModelSettingsRepository repository,
      SettingsEncryptor settingsEncryptor,
      EmbeddingClientFactory clientFactory,
      EmbeddingModelService embeddingModels) {
    this(repository, settingsEncryptor, clientFactory, embeddingModels, System::nanoTime);
  }

  InstalledEmbeddingModel(
      EmbeddingModelSettingsRepository repository,
      SettingsEncryptor settingsEncryptor,
      EmbeddingClientFactory clientFactory,
      EmbeddingModelService embeddingModels,
      LongSupplier nanoTime) {
    this.repository = repository;
    this.settingsEncryptor = settingsEncryptor;
    this.clientFactory = clientFactory;
    this.embeddingModels = embeddingModels;
    this.nanoTime = nanoTime;
    // the takeover of the start has just been tried; the first retry waits its interval
    this.nextTakeover = nanoTime.getAsLong() + FIRST_RETRY.toNanos();
  }

  @Override
  public EmbeddingResponse call(EmbeddingRequest request) {
    return installed().client().call(request);
  }

  @Override
  public float[] embed(Document document) {
    return installed().client().embed(document);
  }

  /**
   * The text the installed client embeds for {@code document}: its formatted content, Kontextpräfix
   * included, not the bare {@link Document#getText()} the interface default returns. Batches go
   * through the default {@code embed(List, EmbeddingOptions, BatchingStrategy)}, which reads this.
   */
  @Override
  public String getEmbeddingContent(Document document) {
    return installed().client().getEmbeddingContent(document);
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
    EmbeddingModelSettings settings =
        repository
            .findInstalled()
            .or(this::takeOverWhenDue)
            .orElseThrow(
                () ->
                    embeddingModels.missingAfterInstallation()
                        ? EmbeddingModelNotInstalledException.afterInstallation()
                        : EmbeddingModelNotInstalledException.pending());
    return build(settings);
  }

  private synchronized Installed build(EmbeddingModelSettings settings) {
    Installed cached = cache.get();
    if (cached != null) {
      return cached;
    }
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

  /**
   * The takeover again, when the backoff allows it and no other caller of this instance is running
   * it; empty otherwise or when it fails. Runs outside every monitor: callers that are not running
   * it are refused at once instead of waiting for the network.
   */
  private Optional<EmbeddingModelSettings> takeOverWhenDue() {
    long now = nanoTime.getAsLong();
    if (now - nextTakeover < 0 || !takeoverRunning.compareAndSet(false, true)) {
      return Optional.empty();
    }
    try {
      if (!embeddingModels.missingAfterInstallation()) {
        return Optional.empty();
      }
      nextTakeover = now + retry.toNanos();
      retry = retry.multipliedBy(2).compareTo(MAX_RETRY) > 0 ? MAX_RETRY : retry.multipliedBy(2);
      return embeddingModels.adoptIfMissing() ? repository.findInstalled() : Optional.empty();
    } finally {
      takeoverRunning.set(false);
    }
  }

  private record Installed(EmbeddingModel client, int dimensions, int maxInputTokens) {}
}
