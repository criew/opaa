package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.llm.ModelDiscovery.Failure;
import io.opaa.security.SettingsEncryptionProperties;
import io.opaa.security.SettingsEncryptor;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link LlmModelDiscoverer} against a real, local {@link HttpServer} standing in for an
 * OpenAI-compatible endpoint and for Ollama, the same building block {@link
 * LlmModelConnectionTesterTest} uses: every outcome the form distinguishes is proven against real
 * HTTP responses, and the stored-key rule against a second server that must never be contacted.
 */
class LlmModelDiscovererTest {

  private static final String OPENAI_MODELS =
      """
      {"object":"list","data":[
        {"id":"whisper-1","object":"model","owned_by":"openai-internal"},
        {"id":"text-embedding-3-small","object":"model","owned_by":"system"},
        {"id":"gpt-4o-mini","object":"model","owned_by":"system"},
        {"id":"gpt-4o","object":"model","owned_by":"system"}
      ]}
      """;

  private static final String OLLAMA_MODELS =
      """
      {"object":"list","data":[
        {"id":"nomic-embed-text:latest","object":"model","owned_by":"library"},
        {"id":"qwen3:8b","object":"model","owned_by":"library"}
      ]}
      """;

  private static final String OLLAMA_TAGS =
      """
      {"models":[
        {"name":"qwen3:8b","model":"qwen3:8b","size":5225388164,
         "details":{"format":"gguf","family":"qwen3","families":["qwen3"],
                    "parameter_size":"8.2B","quantization_level":"Q4_K_M"}},
        {"name":"nomic-embed-text:latest","model":"nomic-embed-text:latest","size":274302450,
         "details":{"format":"gguf","family":"nomic-bert","families":["nomic-bert"],
                    "parameter_size":"137M","quantization_level":"F16"}}
      ]}
      """;

  private HttpServer server;
  private String origin;
  private String baseUrl;
  private LlmModelRepository repository;
  private SettingsEncryptor settingsEncryptor;
  private LlmModelDiscoverer discoverer;
  private final Map<String, String> authorizationByPath = new ConcurrentHashMap<>();

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    origin = "http://127.0.0.1:" + server.getAddress().getPort();
    baseUrl = origin + "/v1";

    repository = mock(LlmModelRepository.class);
    settingsEncryptor =
        new SettingsEncryptor(
            new SettingsEncryptionProperties(
                Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes())));
    discoverer =
        new LlmModelDiscoverer(
            repository, settingsEncryptor, new ObjectMapper(), ModelAddressPolicy.disabled());
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  private void respond(String path, int status, String body) {
    server.createContext(
        path,
        exchange -> {
          authorizationByPath.put(path, String.valueOf(header(exchange, "Authorization")));
          send(exchange, status, body);
        });
  }

  private static String header(HttpExchange exchange, String name) {
    return exchange.getRequestHeaders().getFirst(name);
  }

  private static void send(HttpExchange exchange, int status, String body) throws IOException {
    if (body == null) {
      exchange.sendResponseHeaders(status, -1);
      exchange.close();
      return;
    }
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  /** Answers {@code /api/show} per model from {@code bodies}, keyed by the requested model. */
  private void respondShow(Map<String, String> bodies) {
    server.createContext(
        "/api/show",
        exchange -> {
          authorizationByPath.put("/api/show", String.valueOf(header(exchange, "Authorization")));
          String request =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          String body =
              bodies.entrySet().stream()
                  .filter(entry -> request.contains("\"" + entry.getKey() + "\""))
                  .map(Map.Entry::getValue)
                  .findFirst()
                  .orElse(null);
          if (body == null) {
            send(exchange, 404, "{\"error\":\"model not found\"}");
          } else {
            send(exchange, 200, body);
          }
        });
  }

  private static DiscoveredModel model(ModelDiscovery discovery, String identifier) {
    return discovery.models().stream()
        .filter(m -> m.identifier().equals(identifier))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no model " + identifier + " in " + discovery));
  }

  // --- OpenAI-compatible endpoints ---

  @Test
  void anOpenAiCompatibleEndpointIsListedFromModelsAlone() {
    respond("/v1/models", 200, OPENAI_MODELS);

    ModelDiscovery discovery = discoverer.discover(baseUrl, null, null);

    assertThat(discovery.success()).isTrue();
    assertThat(discovery.failure()).isNull();
    assertThat(discovery.ollama()).isFalse();
    assertThat(discovery.models())
        .extracting(DiscoveredModel::identifier)
        .containsExactly("gpt-4o", "gpt-4o-mini", "text-embedding-3-small", "whisper-1");
    assertThat(model(discovery, "gpt-4o").chatSuitable()).isTrue();
    assertThat(model(discovery, "text-embedding-3-small").chatSuitable()).isFalse();
    assertThat(model(discovery, "whisper-1").chatSuitable()).isFalse();
    assertThat(model(discovery, "gpt-4o").capabilities()).isEmpty();
    assertThat(model(discovery, "gpt-4o").contextLength()).isNull();
    assertThat(discovery.message()).contains("4");
  }

  @Test
  void aContextLengthReportedByTheModelListIsKept() {
    respond(
        "/v1/models",
        200,
        """
        {"object":"list","data":[
          {"id":"meta-llama/Llama-3.1-8B-Instruct","object":"model","max_model_len":131072},
          {"id":"mistral-small","object":"model","context_length":32768}
        ]}
        """);

    ModelDiscovery discovery = discoverer.discover(baseUrl, null, null);

    assertThat(model(discovery, "meta-llama/Llama-3.1-8B-Instruct").contextLength())
        .isEqualTo(131072);
    assertThat(model(discovery, "mistral-small").contextLength()).isEqualTo(32768);
  }

  @Test
  void anEmptyModelListIsASuccessWithoutModels() {
    respond("/v1/models", 200, "{\"object\":\"list\",\"data\":[]}");

    ModelDiscovery discovery = discoverer.discover(baseUrl, null, null);

    assertThat(discovery.success()).isTrue();
    assertThat(discovery.models()).isEmpty();
    assertThat(discovery.message()).contains("keine Modelle");
  }

  @Test
  void aTrailingSlashOnTheBaseUrlIsTolerated() {
    respond("/v1/models", 200, OPENAI_MODELS);

    ModelDiscovery discovery = discoverer.discover(baseUrl + "/", null, null);

    assertThat(discovery.success()).isTrue();
    assertThat(discovery.models()).hasSize(4);
  }

  // --- Ollama ---

  @Test
  void ollamaIsRecognizedAndItsModelsCarrySizeFamilyContextAndCapabilities() {
    respond("/v1/models", 200, OLLAMA_MODELS);
    respond("/api/tags", 200, OLLAMA_TAGS);
    respondShow(
        Map.of(
            "qwen3:8b",
            """
            {"details":{"family":"qwen3","parameter_size":"8.2B"},
             "model_info":{"general.architecture":"qwen3","qwen3.context_length":40960},
             "capabilities":["completion","tools","thinking"]}
            """,
            "nomic-embed-text:latest",
            """
            {"details":{"family":"nomic-bert"},
             "model_info":{"general.architecture":"nomic-bert","nomic-bert.context_length":2048},
             "capabilities":["embedding"]}
            """));

    ModelDiscovery discovery = discoverer.discover(baseUrl, null, null);

    assertThat(discovery.success()).isTrue();
    assertThat(discovery.ollama()).isTrue();
    assertThat(discovery.models())
        .extracting(DiscoveredModel::identifier)
        .containsExactly("qwen3:8b", "nomic-embed-text:latest");

    DiscoveredModel qwen = model(discovery, "qwen3:8b");
    assertThat(qwen.family()).isEqualTo("qwen3");
    assertThat(qwen.parameterSize()).isEqualTo("8.2B");
    assertThat(qwen.quantization()).isEqualTo("Q4_K_M");
    assertThat(qwen.sizeBytes()).isEqualTo(5225388164L);
    assertThat(qwen.contextLength()).isEqualTo(40960);
    assertThat(qwen.capabilities()).containsExactly("completion", "tools", "thinking");
    assertThat(qwen.chatSuitable()).isTrue();

    DiscoveredModel nomic = model(discovery, "nomic-embed-text:latest");
    assertThat(nomic.capabilities()).containsExactly("embedding");
    assertThat(nomic.contextLength()).isEqualTo(2048);
    assertThat(nomic.chatSuitable()).isFalse();
  }

  @Test
  void anOllamaWithoutCapabilitiesFallsBackToTheFamilyForEmbeddingModels() {
    respond("/v1/models", 200, OLLAMA_MODELS);
    respond("/api/tags", 200, OLLAMA_TAGS);
    respondShow(
        Map.of(
            "qwen3:8b",
            "{\"model_info\":{\"qwen3.context_length\":40960}}",
            "nomic-embed-text:latest",
            "{\"model_info\":{\"nomic-bert.context_length\":2048}}"));

    ModelDiscovery discovery = discoverer.discover(baseUrl, null, null);

    assertThat(model(discovery, "qwen3:8b").chatSuitable()).isTrue();
    assertThat(model(discovery, "nomic-embed-text:latest").chatSuitable()).isFalse();
  }

  @Test
  void aFailingDetailCallDegradesToTheBareListInsteadOfFailing() {
    respond("/v1/models", 200, OLLAMA_MODELS);
    respond("/api/tags", 200, OLLAMA_TAGS);
    respondShow(Map.of());

    ModelDiscovery discovery = discoverer.discover(baseUrl, null, null);

    assertThat(discovery.success()).isTrue();
    assertThat(discovery.ollama()).isTrue();
    assertThat(model(discovery, "qwen3:8b").sizeBytes()).isEqualTo(5225388164L);
    assertThat(model(discovery, "qwen3:8b").contextLength()).isNull();
  }

  @Test
  void aHangingDetailCallEndsWithTheBudgetAndKeepsTheBareList() throws IOException {
    // regression guard: the details budget bounds the whole discovery, also when /api/show never
    // answers - the pending calls are cancelled rather than awaited.
    HttpServer ollama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    ollama.setExecutor(Executors.newCachedThreadPool());
    CountDownLatch release = new CountDownLatch(1);
    ollama.createContext("/v1/models", exchange -> send(exchange, 200, OLLAMA_MODELS));
    ollama.createContext("/api/tags", exchange -> send(exchange, 200, OLLAMA_TAGS));
    ollama.createContext(
        "/api/show",
        exchange -> {
          try {
            release.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        });
    ollama.start();
    try {
      LlmModelDiscoverer budgeted =
          new LlmModelDiscoverer(
              repository,
              settingsEncryptor,
              new ObjectMapper(),
              ModelAddressPolicy.disabled(),
              Duration.ofMillis(300));
      String ollamaBaseUrl = "http://127.0.0.1:" + ollama.getAddress().getPort() + "/v1";

      long start = System.nanoTime();
      ModelDiscovery discovery = budgeted.discover(ollamaBaseUrl, null, null);
      Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

      assertThat(elapsed).isLessThan(Duration.ofSeconds(3));
      assertThat(discovery.success()).isTrue();
      assertThat(model(discovery, "qwen3:8b").sizeBytes()).isEqualTo(5225388164L);
      assertThat(model(discovery, "qwen3:8b").contextLength()).isNull();
    } finally {
      release.countDown();
      ollama.stop(0);
    }
  }

  @Test
  void anInterruptedDiscoveryCancelsHangingDetailCallsInsteadOfAwaitingThem() throws Exception {
    // regression guard: an interrupt while awaiting /api/show cancels the pending calls; it must
    // not leave the caller blocked until each call's own request timeout.
    HttpServer ollama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    ollama.setExecutor(Executors.newCachedThreadPool());
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch showReached = new CountDownLatch(1);
    ollama.createContext("/v1/models", exchange -> send(exchange, 200, OLLAMA_MODELS));
    ollama.createContext("/api/tags", exchange -> send(exchange, 200, OLLAMA_TAGS));
    ollama.createContext(
        "/api/show",
        exchange -> {
          showReached.countDown();
          try {
            release.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        });
    ollama.start();
    try {
      LlmModelDiscoverer budgeted =
          new LlmModelDiscoverer(
              repository,
              settingsEncryptor,
              new ObjectMapper(),
              ModelAddressPolicy.disabled(),
              Duration.ofSeconds(20));
      String ollamaBaseUrl = "http://127.0.0.1:" + ollama.getAddress().getPort() + "/v1";
      AtomicReference<ModelDiscovery> result = new AtomicReference<>();
      Thread caller = new Thread(() -> result.set(budgeted.discover(ollamaBaseUrl, null, null)));

      caller.start();
      assertThat(showReached.await(5, TimeUnit.SECONDS)).isTrue();
      long start = System.nanoTime();
      caller.interrupt();
      caller.join(Duration.ofSeconds(8));
      Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

      assertThat(caller.isAlive()).isFalse();
      assertThat(elapsed).isLessThan(Duration.ofSeconds(3));
      assertThat(result.get().failure()).isEqualTo(Failure.UNREACHABLE);
    } finally {
      release.countDown();
      ollama.stop(0);
    }
  }

  @Test
  void anEndpointWithoutTheOllamaApiIsNotTakenForOllama() {
    respond("/v1/models", 200, OLLAMA_MODELS);
    respond("/api/tags", 404, "{\"error\":\"not found\"}");

    ModelDiscovery discovery = discoverer.discover(baseUrl, null, null);

    assertThat(discovery.success()).isTrue();
    assertThat(discovery.ollama()).isFalse();
    assertThat(model(discovery, "nomic-embed-text:latest").chatSuitable()).isFalse();
  }

  // --- Failures ---

  @Test
  void aMissingKeyIsReportedAsNotAuthenticated() {
    respond("/v1/models", 401, "{\"error\":{\"message\":\"Missing bearer\"}}");

    ModelDiscovery discovery = discoverer.discover(baseUrl, null, null);

    assertThat(discovery.success()).isFalse();
    assertThat(discovery.failure()).isEqualTo(Failure.UNAUTHENTICATED);
    assertThat(discovery.message()).contains("401").contains("Zugangsschlüssel");
    assertThat(discovery.models()).isEmpty();
  }

  @Test
  void aRefusedKeyIsReportedAsNotAuthenticated() {
    respond("/v1/models", 403, null);

    ModelDiscovery discovery = discoverer.discover(baseUrl, "sk-wrong", null);

    assertThat(discovery.failure()).isEqualTo(Failure.UNAUTHENTICATED);
    assertThat(discovery.message()).contains("403");
  }

  @Test
  void anUnreachableAddressIsReportedAsUnreachable() {
    server.stop(0);

    ModelDiscovery discovery = discoverer.discover(baseUrl, null, null);

    assertThat(discovery.success()).isFalse();
    assertThat(discovery.failure()).isEqualTo(Failure.UNREACHABLE);
    assertThat(discovery.message()).isNotBlank();
  }

  // regression guard: a hostile endpoint cannot hand the form an unbounded list
  @Test
  void theListIsBoundedInCountAndInTheLengthOfEachEntry() {
    StringBuilder data = new StringBuilder("{\"data\":[{\"id\":\"");
    data.append("x".repeat(LlmModelDiscoverer.MAX_TEXT_LENGTH + 1)).append("\"}");
    for (int i = 0; i < LlmModelDiscoverer.MAX_MODELS + 10; i++) {
      data.append(",{\"id\":\"modell-").append(i).append("\"}");
    }
    respond("/v1/models", 200, data.append("]}").toString());

    ModelDiscovery discovery = discoverer.discover(baseUrl, null, null);

    assertThat(discovery.models()).hasSize(LlmModelDiscoverer.MAX_MODELS);
    assertThat(discovery.models())
        .allSatisfy(
            model ->
                assertThat(model.identifier())
                    .hasSizeLessThanOrEqualTo(LlmModelDiscoverer.MAX_TEXT_LENGTH));
  }

  @Test
  void anOverlongDetailOfOllamaIsLeftOut() {
    respond("/v1/models", 200, "{\"data\":[{\"id\":\"qwen3:8b\"}]}");
    String long_ = "f".repeat(LlmModelDiscoverer.MAX_TEXT_LENGTH + 1);
    respond(
        "/api/tags",
        200,
        "{\"models\":[{\"name\":\"qwen3:8b\",\"details\":{\"family\":\""
            + long_
            + "\",\"parameter_size\":\"8.2B\",\"quantization_level\":\""
            + long_
            + "\"}}]}");

    DiscoveredModel model = discoverer.discover(baseUrl, null, null).models().getFirst();

    assertThat(model.family()).isNull();
    assertThat(model.quantization()).isNull();
    assertThat(model.parameterSize()).isEqualTo("8.2B");
  }

  // regression guard: a key in the query of an address never reaches the log
  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void theLogOfAnUnreachableAddressOmitsItsQuery(CapturedOutput output) {
    server.stop(0);

    discoverer.discover(baseUrl + "?api-key=sk-im-log-verboten", "sk-im-kopf-verboten", null);

    assertThat(output.getAll()).contains("127.0.0.1");
    assertThat(output.getAll()).doesNotContain("sk-im-log-verboten", "sk-im-kopf-verboten");
  }

  @Test
  void anUnlistedInternalAddressIsRefusedBeforeAnyRequest() {
    AtomicBoolean reached = new AtomicBoolean();
    server.createContext("/v1/models", exchange -> reached.set(true));
    LlmModelDiscoverer strict =
        new LlmModelDiscoverer(
            repository,
            settingsEncryptor,
            new ObjectMapper(),
            ModelAddressPolicy.allowing(List.of()));

    ModelDiscovery discovery = strict.discover(baseUrl, null, null);

    assertThat(discovery.success()).isFalse();
    assertThat(discovery.failure()).isEqualTo(Failure.INVALID_ADDRESS);
    assertThat(discovery.message()).contains("OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST");
    assertThat(reached).isFalse();
  }

  // regression guard: removing the stored key must not take the stored address out of the exemption
  @Test
  void theStoredAddressStaysListableWhileTheRemovalOfItsKeyIsRequested() {
    respond("/v1/models", 200, "{\"data\":[{\"id\":\"phi3:mini\"}]}");
    UUID modelId = UUID.randomUUID();
    when(repository.findById(modelId))
        .thenReturn(
            Optional.of(
                new LlmModel("Bestand", baseUrl, "phi3:mini", new BigDecimal("0.70"), 2000, null)));
    LlmModelDiscoverer strict =
        new LlmModelDiscoverer(
            repository,
            settingsEncryptor,
            new ObjectMapper(),
            ModelAddressPolicy.allowing(List.of()));

    ModelDiscovery discovery = strict.discover(baseUrl, null, null, modelId);

    assertThat(discovery.success()).as(discovery.message()).isTrue();
  }

  @Test
  void anEndpointWithoutModelListIsReportedAsSuch() {
    respond("/v1/models", 404, null);

    ModelDiscovery discovery = discoverer.discover(baseUrl, null, null);

    assertThat(discovery.failure()).isEqualTo(Failure.NO_MODEL_LIST);
    assertThat(discovery.message()).contains("Modellliste");
  }

  @Test
  void anAnswerThatIsNoModelListIsReportedAsNoModelList() {
    server.createContext(
        "/v1/models",
        exchange -> {
          byte[] html = "<html><body>Willkommen</body></html>".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "text/html");
          exchange.sendResponseHeaders(200, html.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(html);
          }
        });

    ModelDiscovery discovery = discoverer.discover(baseUrl, null, null);

    assertThat(discovery.failure()).isEqualTo(Failure.NO_MODEL_LIST);
  }

  @Test
  void aServerErrorIsAnUnexpectedResponse() {
    respond("/v1/models", 500, null);

    ModelDiscovery discovery = discoverer.discover(baseUrl, null, null);

    assertThat(discovery.failure()).isEqualTo(Failure.UNEXPECTED_RESPONSE);
    assertThat(discovery.message()).contains("500");
  }

  @Test
  void aModelListBeyondTheSizeLimitIsRejected() {
    String padding = "x".repeat(LlmModelDiscoverer.MAX_RESPONSE_BYTES);
    respond("/v1/models", 200, "{\"data\":[],\"padding\":\"" + padding + "\"}");

    ModelDiscovery discovery = discoverer.discover(baseUrl, null, null);

    assertThat(discovery.failure()).isEqualTo(Failure.UNEXPECTED_RESPONSE);
    assertThat(discovery.message()).contains("zu groß");
  }

  @Test
  void aRedirectIsNotFollowed() throws IOException {
    HttpServer elsewhere = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    elsewhere.start();
    AtomicBoolean elsewhereContacted = new AtomicBoolean(false);
    try {
      elsewhere.createContext(
          "/",
          exchange -> {
            elsewhereContacted.set(true);
            send(exchange, 200, OPENAI_MODELS);
          });
      String target = "http://127.0.0.1:" + elsewhere.getAddress().getPort() + "/v1/models";
      server.createContext(
          "/v1/models",
          exchange -> {
            exchange.getResponseHeaders().add("Location", target);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
          });

      ModelDiscovery discovery = discoverer.discover(baseUrl, "sk-plain", null);

      assertThat(discovery.success()).isFalse();
      assertThat(elsewhereContacted.get()).isFalse();
    } finally {
      elsewhere.stop(0);
    }
  }

  @Test
  void anAddressThatIsNoHttpUrlIsAnInvalidAddress() {
    assertThat(discoverer.discover("ftp://127.0.0.1/v1", null, null).failure())
        .isEqualTo(Failure.INVALID_ADDRESS);
    assertThat(discoverer.discover("keine adresse", null, null).failure())
        .isEqualTo(Failure.INVALID_ADDRESS);
  }

  @Test
  void aBaseUrlWithCredentialsIsRejectedBeforeAnyRequest() {
    String secret = "benutzer:geheim";

    assertThatThrownBy(
            () -> discoverer.discover("https://" + secret + "@modellserver.example/v1", null, null))
        .isInstanceOf(ValidationException.class)
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("geheim"));
  }

  // --- Keys ---

  @Test
  void theGivenKeyIsSentToEveryCallOnTheEndpoint() {
    respond("/v1/models", 200, OLLAMA_MODELS);
    respond("/api/tags", 200, OLLAMA_TAGS);
    respondShow(Map.of("qwen3:8b", "{\"capabilities\":[\"completion\"]}"));

    discoverer.discover(baseUrl, "sk-plain-test-key", null);

    assertThat(authorizationByPath)
        .containsEntry("/v1/models", "Bearer sk-plain-test-key")
        .containsEntry("/api/tags", "Bearer sk-plain-test-key")
        .containsEntry("/api/show", "Bearer sk-plain-test-key");
  }

  @Test
  void withoutAKeyNoAuthorizationHeaderIsSent() {
    respond("/v1/models", 200, OPENAI_MODELS);

    discoverer.discover(baseUrl, null, null);

    assertThat(authorizationByPath).containsEntry("/v1/models", "null");
  }

  @Test
  void theStoredKeyIsUsedForTheModelsOwnOrigin() {
    UUID modelId = UUID.randomUUID();
    when(repository.findById(modelId))
        .thenReturn(Optional.of(storedModel(baseUrl, settingsEncryptor.encrypt("sk-stored"))));
    respond("/v1/models", 200, OPENAI_MODELS);

    discoverer.discover(baseUrl, "", modelId);

    assertThat(authorizationByPath).containsEntry("/v1/models", "Bearer sk-stored");
  }

  @Test
  void theStoredKeyNeverLeavesTheOriginOfItsModel() throws IOException {
    UUID modelId = UUID.randomUUID();
    when(repository.findById(modelId))
        .thenReturn(Optional.of(storedModel(baseUrl, settingsEncryptor.encrypt("sk-stored"))));

    HttpServer attacker = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    attacker.start();
    AtomicBoolean attackerContacted = new AtomicBoolean(false);
    try {
      attacker.createContext(
          "/",
          exchange -> {
            attackerContacted.set(true);
            send(exchange, 200, OPENAI_MODELS);
          });
      String attackerBaseUrl = "http://127.0.0.1:" + attacker.getAddress().getPort() + "/v1";

      assertThatThrownBy(() -> discoverer.discover(attackerBaseUrl, null, modelId))
          .isInstanceOf(ValidationException.class);
      assertThat(attackerContacted.get())
          .as("an origin other than the model's own must never receive its stored key")
          .isFalse();
    } finally {
      attacker.stop(0);
    }
  }

  @Test
  void aModelWithoutStoredKeyIsListedWithoutKeyAtAnyAddress() {
    UUID modelId = UUID.randomUUID();
    when(repository.findById(modelId))
        .thenReturn(Optional.of(storedModel("http://elsewhere.example/v1", null)));
    respond("/v1/models", 200, OPENAI_MODELS);

    ModelDiscovery discovery = discoverer.discover(baseUrl, null, modelId);

    assertThat(discovery.success()).isTrue();
    assertThat(authorizationByPath).containsEntry("/v1/models", "null");
  }

  @Test
  void anUnknownModelIdIsRejectedWith404() {
    UUID unknown = UUID.randomUUID();
    when(repository.findById(unknown)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> discoverer.discover(baseUrl, null, unknown))
        .isInstanceOf(NotFoundException.class);
  }

  private static LlmModel storedModel(String storedBaseUrl, String ciphertext) {
    return new LlmModel(
        "Gespeichert", storedBaseUrl, "gpt-4o", new BigDecimal("0.70"), 2000, ciphertext);
  }

  @Test
  void modelsAreSortedChatSuitableFirstThenByIdentifier() {
    respond(
        "/v1/models",
        200,
        """
        {"data":[{"id":"b-embed"},{"id":"c-chat"},{"id":"a-chat"},{"id":"a-embedding"}]}
        """);

    List<String> order =
        discoverer.discover(baseUrl, null, null).models().stream()
            .map(DiscoveredModel::identifier)
            .toList();

    assertThat(order).containsExactly("a-chat", "c-chat", "a-embedding", "b-embed");
  }
}
