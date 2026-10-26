package io.opaa.llm;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.opaa.compliance.DataFlow;
import io.opaa.instance.InvalidationBus;
import io.opaa.instance.InvalidationSubscriber;
import io.opaa.security.SettingsEncryptor;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Resolves the {@link ChatClient} of the systemwide active {@link LlmModel} per call, so an
 * activation takes effect without a restart. Clients are built programmatically over the one
 * OpenAI-compatible path, wrapped in {@link ReasoningTextStrippingChatModel} and handed the
 * application's {@link ObservationRegistry} and {@link MeterRegistry}, so chat metrics stay
 * visible.
 *
 * <p>The client is cached until {@link ActiveChatModelChangedEvent} arrives after commit, or until
 * the {@link InvalidationBus} announces a change of {@code llm_models} on any instance (ADR-0053,
 * Entscheidung 5); while the bus does not listen, every call builds a client of its own. {@link
 * #generation} guarantees that a build running across an invalidation is returned but never cached.
 * The decrypted access key lives only inside the built client, which follows no redirect ({@link
 * ModelRedirectRefusal}).
 */
@DataFlow("chat-model")
@Component
public class ActiveChatModelResolver implements InvalidationSubscriber {

  /** The topic the trigger on {@code llm_models} announces. */
  public static final String INVALIDATION_TOPIC = "chat-model";

  private static final Logger log = LoggerFactory.getLogger(ActiveChatModelResolver.class);

  private final LlmModelRepository repository;
  private final SettingsEncryptor settingsEncryptor;
  private final ObjectProvider<ObservationRegistry> observationRegistryProvider;
  private final ObjectProvider<MeterRegistry> meterRegistryProvider;
  private final InvalidationBus bus;
  private final AtomicReference<Resolved> cache = new AtomicReference<>();

  /**
   * The same client with a hard request timeout, for the model-backed metadata extraction: an
   * abandoned call must end and free its thread. Cached beside {@link #cache} and invalidated with
   * it; keyed by the timeout, of which there is one in practice.
   */
  private final AtomicReference<TimedResolved> timedCache = new AtomicReference<>();

  private final AtomicLong generation = new AtomicLong();
  private final AtomicInteger buildCount = new AtomicInteger();

  /**
   * Whether the "no active model" condition was already logged since it last cleared: one ERROR per
   * state change, not per request on this hot path.
   */
  private final AtomicBoolean noActiveModelLogged = new AtomicBoolean(false);

  /**
   * Test-only seam: runs after {@link #activeModel()} but before the built {@link ChatClient} is
   * committed to {@link #cache}, so a test can pause a build exactly inside the race window {@link
   * #generation} closes and inject a concurrent invalidation there. A no-op in production.
   */
  private volatile Runnable testRaceWindowHook = () -> {};

  public ActiveChatModelResolver(
      LlmModelRepository repository,
      SettingsEncryptor settingsEncryptor,
      ObjectProvider<ObservationRegistry> observationRegistryProvider,
      ObjectProvider<MeterRegistry> meterRegistryProvider,
      InvalidationBus bus) {
    this.repository = repository;
    this.settingsEncryptor = settingsEncryptor;
    this.observationRegistryProvider = observationRegistryProvider;
    this.meterRegistryProvider = meterRegistryProvider;
    this.bus = bus;
  }

  /**
   * The {@link ChatClient} for the systemwide active model - cached until the active model changes.
   *
   * @throws NoActiveChatModelException when {@code llm_models} has no active row
   */
  public ChatClient resolveChatClient() {
    return resolve().chatClient();
  }

  /**
   * Base address and model identifier of the active model, read from {@code llm_models} without
   * building a client or decrypting the key.
   *
   * @throws NoActiveChatModelException when {@code llm_models} has no active row
   */
  public ActiveChatModelDescription resolveDescription() {
    LlmModel model = activeModel();
    return new ActiveChatModelDescription(model.getBaseUrl(), model.getModelIdentifier());
  }

  /**
   * The {@link ChatClient} of the same active model, but with {@code requestTimeout} as a hard
   * limit on the HTTP request. Without it a hanging model would hold the calling thread forever -
   * the caller's own {@code Future.get(timeout)} bounds only its waiting, never the request.
   *
   * @throws NoActiveChatModelException when {@code llm_models} has no active row
   */
  public ChatClient resolveChatClient(Duration requestTimeout) {
    if (!bus.isListening()) {
      return newChatClient(activeModel(), requestTimeout);
    }
    TimedResolved cached = timedCache.get();
    if (cached != null && cached.timeout().equals(requestTimeout)) {
      return cached.chatClient();
    }
    return buildAndCacheTimed(requestTimeout).chatClient();
  }

  private Resolved resolve() {
    if (!bus.isListening()) {
      return new Resolved(newChatClient(activeModel(), null));
    }
    Resolved cached = cache.get();
    if (cached != null) {
      return cached;
    }
    return buildAndCache();
  }

  private synchronized Resolved buildAndCache() {
    // Double-checked under the monitor: two threads racing past the unsynchronized read in
    // resolve() must not each build (and log) their own client.
    Resolved cached = cache.get();
    if (cached != null) {
      return cached;
    }
    long generationAtStart = generation.get();
    LlmModel model = activeModel();
    testRaceWindowHook.run();
    Resolved resolved = new Resolved(newChatClient(model, null));
    // Only cache the result if nothing invalidated the cache while this build was running.
    if (generation.get() == generationAtStart) {
      cache.set(resolved);
      buildCount.incrementAndGet();
      log.info(
          "Built chat client for active model '{}' at {}",
          model.getModelIdentifier(),
          ModelEndpointUri.withoutSecrets(model.getBaseUrl()));
    } else {
      log.info(
          "Discarding a chat client built for model '{}' - the active model changed while it was"
              + " being built; the next resolution will rebuild against the now-current one.",
          model.getModelIdentifier());
    }
    return resolved;
  }

  private synchronized TimedResolved buildAndCacheTimed(Duration requestTimeout) {
    TimedResolved cached = timedCache.get();
    if (cached != null && cached.timeout().equals(requestTimeout)) {
      return cached;
    }
    long generationAtStart = generation.get();
    LlmModel model = activeModel();
    TimedResolved resolved =
        new TimedResolved(requestTimeout, newChatClient(model, requestTimeout));
    // The same commit rule as buildAndCache: a build whose generation changed underneath it is
    // handed to its own caller but never cached.
    if (generation.get() == generationAtStart) {
      timedCache.set(resolved);
      log.info(
          "Built chat client with a {} request timeout for active model '{}' at {}",
          requestTimeout,
          model.getModelIdentifier(),
          ModelEndpointUri.withoutSecrets(model.getBaseUrl()));
    }
    return resolved;
  }

  /** A client for {@code model}; {@code requestTimeout} may be {@code null} for none. */
  private ChatClient newChatClient(LlmModel model, Duration requestTimeout) {
    ObservationRegistry observationRegistry =
        observationRegistryProvider.getIfUnique(() -> ObservationRegistry.NOOP);
    OpenAiChatModel chatModel =
        chatModelBuilder(model, observationRegistry, requestTimeout).build();
    return ChatClient.builder(
            new ReasoningTextStrippingChatModel(chatModel), observationRegistry, null, null)
        .build();
  }

  /**
   * The builder both clients share; only {@code requestTimeout} distinguishes them. It goes onto
   * the options, not onto the HTTP client: the SDK carries a per-request timeout that overrides
   * whatever the client was built with, so a timeout set there would be ignored.
   */
  private OpenAiChatModel.Builder chatModelBuilder(
      LlmModel model, ObservationRegistry observationRegistry, Duration requestTimeout) {
    String apiKey =
        model.getApiKeyCiphertext() == null
            ? ""
            : settingsEncryptor.decrypt(model.getApiKeyCiphertext());
    OpenAiChatOptions.Builder optionsBuilder =
        OpenAiChatOptions.builder()
            .baseUrl(model.getBaseUrl())
            .apiKey(apiKey)
            .model(model.getModelIdentifier())
            .maxTokens(model.getMaxTokens());
    // Without a stored temperature the request carries none: some models reject the parameter.
    if (model.getTemperature() != null) {
      optionsBuilder.temperature(model.getTemperature().doubleValue());
    }
    if (requestTimeout != null) {
      optionsBuilder.timeout(requestTimeout);
    }
    OpenAiChatOptions options = optionsBuilder.build();
    return OpenAiChatModel.builder()
        .options(options)
        .observationRegistry(observationRegistry)
        .meterRegistry(meterRegistryProvider.getIfAvailable())
        .httpClientBuilderCustomizer(ModelRedirectRefusal.INSTANCE);
  }

  private LlmModel activeModel() {
    List<LlmModel> active = repository.findAllByActiveTrue();
    if (active.isEmpty()) {
      if (noActiveModelLogged.compareAndSet(false, true)) {
        log.error(
            "No active chat model configured - every chat request will fail until a SYSTEM_ADMIN"
                + " activates one via the model administration screen.");
      }
      throw new NoActiveChatModelException();
    }
    return active.get(0);
  }

  /**
   * Drops both cached clients after the change committed, advances {@link #generation} so a build
   * in flight is not cached, and re-arms the "no active model" ERROR.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  void onActiveModelChanged(ActiveChatModelChangedEvent event) {
    invalidateAll();
  }

  @Override
  public Set<String> invalidationTopics() {
    return Set.of(INVALIDATION_TOPIC);
  }

  @Override
  public void invalidateAll() {
    generation.incrementAndGet();
    cache.set(null);
    timedCache.set(null);
    noActiveModelLogged.set(false);
  }

  /** How many times a {@link ChatClient} was actually built and cached; observable by tests. */
  int builtClientCount() {
    return buildCount.get();
  }

  /**
   * Test-only: clears both caches, the build count, the logging state and the race-window hook, for
   * tests that change {@code llm_models} directly and so publish no {@link
   * ActiveChatModelChangedEvent}.
   */
  void resetForTest() {
    cache.set(null);
    timedCache.set(null);
    buildCount.set(0);
    noActiveModelLogged.set(false);
    testRaceWindowHook = () -> {};
  }

  /**
   * Test-only: installs {@code hook} to run inside {@link #buildAndCache} right after {@link
   * #activeModel()} returns but before the built client is committed to {@link #cache} - the exact
   * window a concurrent invalidation must survive (see this class's own Javadoc).
   */
  void setTestRaceWindowHook(Runnable hook) {
    this.testRaceWindowHook = hook;
  }

  private record Resolved(ChatClient chatClient) {}

  private record TimedResolved(Duration timeout, ChatClient chatClient) {}
}
