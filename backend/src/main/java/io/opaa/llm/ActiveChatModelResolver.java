package io.opaa.llm;

import io.opaa.security.SettingsEncryptor;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
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
 * the same OpenAI-compatible protocol.
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
 * <p><b>The decrypted access key never outlives {@link #buildAndCache}.</b> It is a local variable
 * used only to construct the {@link OpenAiChatOptions} handed to {@link OpenAiChatModel#builder()};
 * the built {@link ChatClient} kept in {@link #cache} carries it only inside the OpenAI Java SDK
 * client it wraps, exactly as any other in-memory HTTP client necessarily must - this class itself
 * never stores or logs the plaintext value.
 */
@Component
public class ActiveChatModelResolver {

  private static final Logger log = LoggerFactory.getLogger(ActiveChatModelResolver.class);

  private final LlmModelRepository repository;
  private final SettingsEncryptor settingsEncryptor;
  private final AtomicReference<Resolved> cache = new AtomicReference<>();
  private final AtomicInteger buildCount = new AtomicInteger();

  public ActiveChatModelResolver(
      LlmModelRepository repository, SettingsEncryptor settingsEncryptor) {
    this.repository = repository;
    this.settingsEncryptor = settingsEncryptor;
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
    LlmModel model = activeModel();
    String apiKey =
        model.getApiKeyCiphertext() == null
            ? ""
            : settingsEncryptor.decrypt(model.getApiKeyCiphertext());
    OpenAiChatOptions options =
        OpenAiChatOptions.builder()
            .baseUrl(model.getBaseUrl())
            .apiKey(apiKey)
            .model(model.getModelIdentifier())
            .temperature(model.getTemperature().doubleValue())
            .maxTokens(model.getMaxTokens())
            .build();
    ChatClient chatClient =
        ChatClient.builder(OpenAiChatModel.builder().options(options).build()).build();
    Resolved resolved = new Resolved(chatClient, model.getBaseUrl(), model.getModelIdentifier());
    cache.set(resolved);
    buildCount.incrementAndGet();
    log.info(
        "Built chat client for active model '{}' at {}",
        model.getModelIdentifier(),
        model.getBaseUrl());
    return resolved;
  }

  private LlmModel activeModel() {
    List<LlmModel> active = repository.findAllByActiveTrue();
    if (active.isEmpty()) {
      log.error(
          "No active chat model configured - every chat request will fail until a SYSTEM_ADMIN"
              + " activates one via the model administration screen.");
      throw new NoActiveChatModelException();
    }
    return active.get(0);
  }

  /**
   * Drops the cached client so the next {@link #resolveChatClient()}/{@link #resolveDescription()}
   * call re-reads {@code llm_models} - fired only after the triggering change actually committed
   * (see this class's own Javadoc).
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  void onActiveModelChanged(ActiveChatModelChangedEvent event) {
    cache.set(null);
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
   * rebuild and resets {@link #builtClientCount()} to zero. A test that manipulates {@code
   * llm_models} directly (e.g. via JDBC, to reset fixtures between test methods sharing one Spring
   * context) bypasses {@link LlmModelService} entirely, so no {@link ActiveChatModelChangedEvent}
   * fires - without this, the cache would keep serving whatever client an earlier test built,
   * exactly the staleness this class exists to prevent for a genuine activation.
   */
  void resetForTest() {
    cache.set(null);
    buildCount.set(0);
  }

  private record Resolved(ChatClient chatClient, String baseUrl, String modelIdentifier) {}
}
