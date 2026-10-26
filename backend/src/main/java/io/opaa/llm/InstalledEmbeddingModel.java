package io.opaa.llm;

import io.opaa.compliance.DataFlow;
import io.opaa.instance.InvalidationSubscriber;
import io.opaa.security.SettingsEncryptor;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * The application's {@link EmbeddingModel}: the client of the installed {@link
 * EmbeddingModelSettings}. Every call before an embedding model is installed throws {@link
 * EmbeddingModelNotInstalledException}, so nothing is embedded into a vector table that does not
 * exist yet.
 *
 * <p>The client is cached from the first call that finds the row, together with the row's
 * generation; the absence is never cached. The {@link io.opaa.instance.InvalidationBus} drops the
 * client when the row changes on any instance (ADR-0053, Entscheidung 5), and a client built from a
 * row read before such an invalidation is used for its one call but never cached, so the cache
 * never holds a model the row no longer names.
 *
 * <p>A completed installation of the {@code dev} mode without a model - its self-installation at
 * the start found the endpoint unreachable - tries it again on use, at most once per backoff
 * interval of this instance, doubling from {@link #FIRST_RETRY} to {@link #MAX_RETRY} (ADR-0053:
 * "je Instanz"; the installation itself runs under the schema lock). Every other mode installs
 * nothing here.
 */
@DataFlow("embedding-model")
@Component
public class InstalledEmbeddingModel
    implements EmbeddingModel, EmbeddingClients, InvalidationSubscriber {

  /** The topic the trigger on {@code embedding_model} announces. */
  public static final String INVALIDATION_TOPIC = "embedding-model";

  static final Duration FIRST_RETRY = Duration.ofSeconds(30);
  static final Duration MAX_RETRY = Duration.ofMinutes(10);

  private static final Logger log = LoggerFactory.getLogger(InstalledEmbeddingModel.class);

  private final EmbeddingModelSettingsRepository repository;
  private final SettingsEncryptor settingsEncryptor;
  private final EmbeddingClientFactory clientFactory;
  private final EmbeddingModelService embeddingModels;
  private final ApplicationEventPublisher events;
  private final LongSupplier nanoTime;
  private final AtomicReference<Installed> cache = new AtomicReference<>();
  private final AtomicLong invalidations = new AtomicLong();
  private final AtomicBoolean takeoverRunning = new AtomicBoolean();
  private volatile long nextTakeover;
  private volatile Duration retry = FIRST_RETRY;

  @Autowired
  public InstalledEmbeddingModel(
      EmbeddingModelSettingsRepository repository,
      SettingsEncryptor settingsEncryptor,
      EmbeddingClientFactory clientFactory,
      EmbeddingModelService embeddingModels,
      ApplicationEventPublisher events) {
    this(repository, settingsEncryptor, clientFactory, embeddingModels, events, System::nanoTime);
  }

  InstalledEmbeddingModel(
      EmbeddingModelSettingsRepository repository,
      SettingsEncryptor settingsEncryptor,
      EmbeddingClientFactory clientFactory,
      EmbeddingModelService embeddingModels,
      ApplicationEventPublisher events,
      LongSupplier nanoTime) {
    this.events = events;
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
    Installed installed = installed();
    EmbeddingResponse response = installed.client().call(request);
    announceMissingAnchor(installed);
    return response;
  }

  @Override
  public float[] embed(Document document) {
    Installed installed = installed();
    float[] vector = installed.client().embed(document);
    announceMissingAnchor(installed);
    return vector;
  }

  /**
   * After the first successful embedding of a model without a reference anchor - the status probe
   * among them - asks once per cached client for the anchor to be taken (ADR-0061, Entscheidung 5).
   */
  private void announceMissingAnchor(Installed installed) {
    if (installed.anchorMissing().compareAndSet(true, false)) {
      events.publishEvent(new EmbeddingAnchorMissingEvent(installed.generation()));
    }
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
  public Pinned pin() {
    Installed installed = installed();
    return new Pinned(installed.client(), installed.generation(), installed.maxInputTokens());
  }

  @Override
  public Set<String> invalidationTopics() {
    return Set.of(INVALIDATION_TOPIC);
  }

  @Override
  public void invalidateAll() {
    invalidations.incrementAndGet();
    cache.set(null);
  }

  private Installed installed() {
    Installed cached = cache.get();
    if (cached != null) {
      return cached;
    }
    long seen = invalidations.get();
    EmbeddingModelSettings settings =
        repository
            .findInstalled()
            .or(this::takeOverWhenDue)
            .orElseThrow(
                () ->
                    embeddingModels.missingAfterInstallation()
                        ? EmbeddingModelNotInstalledException.afterInstallation()
                        : EmbeddingModelNotInstalledException.pending());
    return build(settings, seen);
  }

  private synchronized Installed build(EmbeddingModelSettings settings, long seen) {
    Installed cached = cache.get();
    if (cached != null && cached.generation() == settings.getGeneration()) {
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
            clientFactory.create(endpoint),
            settings.getDimensions(),
            settings.getMaxInputTokens(),
            settings.getGeneration(),
            new AtomicBoolean(!settings.hasReferenceAnchor()));
    if (invalidations.get() == seen) {
      cache.set(built);
    }
    log.info(
        "Built embedding client for model '{}' (generation {}) at {}",
        settings.getModelIdentifier(),
        settings.getGeneration(),
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

  private record Installed(
      EmbeddingModel client,
      int dimensions,
      int maxInputTokens,
      long generation,
      AtomicBoolean anchorMissing) {}
}
