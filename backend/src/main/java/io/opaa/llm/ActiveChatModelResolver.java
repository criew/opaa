package io.opaa.llm;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.opaa.security.SettingsEncryptor;
import java.time.Duration;
import java.util.List;
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
 * Resolves the {@link ChatClient} for the systemwide active {@link LlmModel} at runtime (#758,
 * docs/features/llm-integration.md#stufe-1-verwaltete-chat-modelle-in-umsetzung), replacing what
 * used to be a {@code ChatClient.Builder} built once at startup from the static Spring AI OpenAI
 * autoconfiguration. {@code io.opaa.query.AnswerGenerationService} and {@code
 * io.opaa.chat.ChatTitleGenerationService} both call {@link #resolveChatClient()} on every request
 * instead of holding a {@code ChatClient} field built in their constructor - the only way an
 * activation via the admin API (#764) can take effect without a restart.
 *
 * <p><b>Built programmatically, not through Spring Boot's OpenAI autoconfiguration</b> - {@link
 * OpenAiChatModel#builder()} with an {@link OpenAiChatOptions} carrying the active model's own
 * {@code baseUrl}/{@code apiKey}/{@code model}/{@code temperature}/{@code maxTokens}. There is
 * exactly one connection path (docs/features/llm-integration.md#ein-anbindungsweg-nicht-zwei): no
 * provider switch, since every managed model - including Ollama's own {@code /v1} endpoint - speaks
 * the same OpenAI-compatible protocol. The application's own {@link ObservationRegistry}/{@link
 * MeterRegistry} beans (if any) are passed to both {@link OpenAiChatModel#builder()} and {@link
 * ChatClient#builder(org.springframework.ai.chat.model.ChatModel, ObservationRegistry,
 * org.springframework.ai.chat.client.observation.ChatClientObservationConvention,
 * org.springframework.ai.chat.client.observation.AdvisorObservationConvention)} - a client built
 * with the two-argument {@code ChatClient.builder(chatModel)} overload would silently use {@link
 * ObservationRegistry#NOOP} instead, and every chat span/metric this application otherwise exposes
 * on {@code /actuator/prometheus} would vanish without any error (#767 review, finding 3).
 *
 * <p><b>Cached per active model, invalidated on change.</b> Building a {@link ChatClient} means
 * constructing a real HTTP client (no network call happens until the first request, see {@code
 * org.springframework.ai.openai.setup.OpenAiSetup}), so repeated requests reuse the same instance
 * rather than paying that cost per call - {@link #resolveChatClient()} only rebuilds when {@link
 * #cache} is empty. {@link LlmModelService} publishes {@link ActiveChatModelChangedEvent} after
 * every commit that activates a different model or changes the currently active one; {@link
 * #onActiveModelChanged} - a {@link TransactionalEventListener} bound to {@link
 * TransactionPhase#AFTER_COMMIT} - clears the cache in reaction, never before the change is
 * actually durable: a rolled-back activation must not have invalidated a still-correct cache.
 *
 * <p><b>Race between an in-flight build and a concurrent invalidation (#767 review, blocking
 * finding).</b> {@link #buildAndCache} is the only writer of {@link #cache} with a genuine value
 * (the listener only ever clears it), but it is not the only thing that can run concurrently with
 * it: {@link #onActiveModelChanged} fires on whatever thread commits the triggering change, fully
 * independently of any thread mid-build. Without a safeguard, this sequence is possible - Thread A
 * reads the (still) active model X inside {@link #buildAndCache}; a second model is activated and
 * committed, whose listener callback clears the cache; Thread A then finishes building its (already
 * outdated) client for X and overwrites the just-cleared cache with it - leaving a permanently
 * stale client behind that nothing but the *next* change would evict, silently and without any
 * error. {@link #generation} closes this window: {@link #buildAndCache} records the generation
 * before it starts reading {@code llm_models} and only commits its result to {@link #cache} if the
 * generation is still the same afterwards; {@link #onActiveModelChanged} increments it in the same
 * atomic step as clearing the cache. A build whose generation changed underneath it is discarded
 * (but still returned to its own caller - a single request served by a to-be-superseded client is
 * harmless; the next {@link #resolveChatClient()} call rebuilds against the now-current active
 * model) rather than cached, so no completed build can ever undo a concurrent invalidation,
 * regardless of the order the two threads happen to run in.
 *
 * <p><b>The decrypted access key never outlives {@link #buildAndCache}.</b> It is a local variable
 * used only to construct the {@link OpenAiChatOptions} handed to {@link OpenAiChatModel#builder()};
 * the built {@link ChatClient} kept in {@link #cache} carries it only inside the OpenAI Java SDK
 * client it wraps, exactly as any other in-memory HTTP client necessarily must - this class itself
 * never stores or logs the plaintext value.
 *
 * <p><b>A discarded {@link ChatClient} (superseded build above, or an edit/activation that replaces
 * a still-cached one) is never explicitly closed.</b> The OpenAI Java SDK client it wraps holds an
 * OkHttp {@code ConnectionPool}, which times out and reclaims idle connections on its own (OkHttp's
 * default keep-alive is five minutes); building a client that is never actually used for a request
 * never opens a connection to begin with. Closing it explicitly would need this class to build the
 * underlying {@code OpenAIClient} itself (see {@code
 * org.springframework.ai.openai.setup.OpenAiSetup}) instead of delegating to {@link
 * OpenAiChatModel.Builder}, purely to hold a reference capable of closing it - a structural change
 * not justified by a resource that already bounds itself (#767 review, optional finding 8).
 */
@Component
public class ActiveChatModelResolver {

  private static final Logger log = LoggerFactory.getLogger(ActiveChatModelResolver.class);

  private final LlmModelRepository repository;
  private final SettingsEncryptor settingsEncryptor;
  private final ObjectProvider<ObservationRegistry> observationRegistryProvider;
  private final ObjectProvider<MeterRegistry> meterRegistryProvider;
  private final AtomicReference<Resolved> cache = new AtomicReference<>();

  /**
   * The same client with a hard request timeout, for the model-backed metadata extraction (#1073):
   * an abandoned call must end and free its thread. Cached beside {@link #cache} and invalidated
   * with it; keyed by the timeout, of which there is one in practice.
   */
  private final AtomicReference<TimedResolved> timedCache = new AtomicReference<>();

  private final AtomicLong generation = new AtomicLong();
  private final AtomicInteger buildCount = new AtomicInteger();

  /**
   * Whether the "no active model" condition was already logged at ERROR since it last cleared -
   * {@link #activeModel()} is on the hot path of every chat request and of every health check poll
   * (#767 review, finding 5), so without this an installation with no active model would produce
   * one ERROR log line per second rather than one per actual state change.
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
      ObjectProvider<MeterRegistry> meterRegistryProvider) {
    this.repository = repository;
    this.settingsEncryptor = settingsEncryptor;
    this.observationRegistryProvider = observationRegistryProvider;
    this.meterRegistryProvider = meterRegistryProvider;
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
   * Basis-Adresse and Modell-Kennung of the systemwide active model, for {@code
   * io.opaa.observability.ChatHealthIndicator} - reads {@code llm_models} directly rather than
   * through {@link #cache}, so a health check never triggers building a full {@link ChatClient}
   * (and never needs {@link SettingsEncryptor} to be configured) just to report these two values.
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
    TimedResolved cached = timedCache.get();
    if (cached != null && cached.timeout().equals(requestTimeout)) {
      return cached.chatClient();
    }
    return buildAndCacheTimed(requestTimeout).chatClient();
  }

  private Resolved resolve() {
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
    ObservationRegistry observationRegistry =
        observationRegistryProvider.getIfUnique(() -> ObservationRegistry.NOOP);
    OpenAiChatModel chatModel = chatModelBuilder(model, observationRegistry, null).build();
    ChatClient chatClient = ChatClient.builder(chatModel, observationRegistry, null, null).build();
    Resolved resolved = new Resolved(chatClient);
    // See this class's own Javadoc ("Race between an in-flight build and a concurrent
    // invalidation"): only commit if nothing invalidated the cache while this build was running.
    if (generation.get() == generationAtStart) {
      cache.set(resolved);
      buildCount.incrementAndGet();
      log.info(
          "Built chat client for active model '{}' at {}",
          model.getModelIdentifier(),
          model.getBaseUrl());
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
    ObservationRegistry observationRegistry =
        observationRegistryProvider.getIfUnique(() -> ObservationRegistry.NOOP);
    OpenAiChatModel chatModel =
        chatModelBuilder(model, observationRegistry, requestTimeout).build();
    TimedResolved resolved =
        new TimedResolved(
            requestTimeout, ChatClient.builder(chatModel, observationRegistry, null, null).build());
    // The same commit rule as buildAndCache: a build whose generation changed underneath it is
    // handed to its own caller but never cached.
    if (generation.get() == generationAtStart) {
      timedCache.set(resolved);
      log.info(
          "Built chat client with a {} request timeout for active model '{}' at {}",
          requestTimeout,
          model.getModelIdentifier(),
          model.getBaseUrl());
    }
    return resolved;
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
            .temperature(model.getTemperature().doubleValue())
            .maxTokens(model.getMaxTokens());
    if (requestTimeout != null) {
      optionsBuilder.timeout(requestTimeout);
    }
    OpenAiChatOptions options = optionsBuilder.build();
    return OpenAiChatModel.builder()
        .options(options)
        .observationRegistry(observationRegistry)
        .meterRegistry(meterRegistryProvider.getIfAvailable());
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
   * Drops the cached client so the next {@link #resolveChatClient()}/{@link #resolveDescription()}
   * call re-reads {@code llm_models} - fired only after the triggering change actually committed
   * (see this class's own Javadoc). Also advances {@link #generation}, the safeguard against a
   * build already in flight overwriting this invalidation with a stale result (see this class's own
   * Javadoc), and resets {@link #noActiveModelLogged}: whatever just changed may have resolved a
   * previous "no active model" condition, so that state deserves a fresh ERROR the next time it
   * actually recurs.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  void onActiveModelChanged(ActiveChatModelChangedEvent event) {
    generation.incrementAndGet();
    cache.set(null);
    timedCache.set(null);
    noActiveModelLogged.set(false);
  }

  /**
   * How many times a {@link ChatClient} was actually (re)built - test-observable proof that
   * repeated calls without an intervening change reuse the cache rather than rebuilding per call
   * (#758 acceptance criterion), which a time-based assertion could not distinguish from "rebuilt,
   * but fast".
   */
  int builtClientCount() {
    return buildCount.get();
  }

  /**
   * Test-only: forces the next {@link #resolveChatClient()}/{@link #resolveDescription()} call to
   * rebuild, resets {@link #builtClientCount()} to zero and clears the "no active model" logging
   * state - a test that manipulates {@code llm_models} directly (e.g. via JDBC, to reset fixtures
   * between test methods sharing one Spring context) bypasses {@link LlmModelService} entirely, so
   * no {@link ActiveChatModelChangedEvent} fires - without this, the cache would keep serving
   * whatever client an earlier test built, exactly the staleness this class exists to prevent for a
   * genuine activation. Also restores {@link #testRaceWindowHook} to a no-op, so a race-window test
   * cannot leak its hook into an unrelated later test sharing this class's Spring context.
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
