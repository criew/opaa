package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openai.errors.UnexpectedStatusCodeException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.opaa.instance.InvalidationBus;
import io.opaa.security.SettingsEncryptor;
import io.opaa.test.OutboundProxyRoute;
import io.opaa.test.RecordingHttpProxy;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The runtime clients of the chat and embedding roles, built exactly as {@link
 * ActiveChatModelResolver} and {@link OpenAiEmbeddingClientFactory} build them, follow no redirect:
 * the redirect target is never contacted, with or without an access key and for the client with a
 * request timeout. Proxy and timeout behaviour stay intact. OPAA's chat model does not stream.
 */
class ModelClientRedirectTest {

  private static final String CHAT_COMPLETION =
      """
      {"id": "chatcmpl-test", "object": "chat.completion", "created": 1700000000,
       "model": "model-r",
       "choices": [{"index": 0, "message": {"role": "assistant", "content": "Vom Ziel"},
                    "finish_reason": "stop"}],
       "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}
      """;

  private static final String EMBEDDING =
      """
      {"object": "list", "model": "bge-m3",
       "data": [{"object": "embedding", "index": 0, "embedding": [0.1, 0.2, 0.3]}],
       "usage": {"prompt_tokens": 1, "total_tokens": 1}}
      """;

  private final ExecutorService serverThreads = Executors.newCachedThreadPool();
  private final CountDownLatch releaseHangingHandlers = new CountDownLatch(1);
  private HttpServer origin;
  private HttpServer target;
  private final AtomicInteger originRequests = new AtomicInteger();
  private final AtomicInteger targetRequests = new AtomicInteger();

  @BeforeEach
  void startServers() throws IOException {
    origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    origin.setExecutor(serverThreads);
    origin.start();
    target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    target.setExecutor(serverThreads);
    target.createContext(
        "/",
        exchange -> {
          targetRequests.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          String path = exchange.getRequestURI().getPath();
          send(exchange, 200, path.endsWith("/embeddings") ? EMBEDDING : CHAT_COMPLETION);
        });
    target.start();
  }

  @AfterEach
  void stopServers() {
    releaseHangingHandlers.countDown();
    origin.stop(0);
    target.stop(0);
    serverThreads.shutdownNow();
  }

  // regression guard for the embedding role: a 3xx never reaches an address the target check never
  // saw
  @ParameterizedTest
  @ValueSource(ints = {301, 302, 303, 307, 308})
  void theEmbeddingClientFollowsNoRedirect(int status) {
    redirectEverything(status);
    EmbeddingModel client =
        embeddingClientFactory()
            .create(new EmbeddingEndpoint(originBaseUrl(), "bge-m3", "sk-geheim", 2048));

    assertThatThrownBy(() -> client.embed("vertraulicher Text"))
        .satisfies(e -> assertRefusedWithStatus(e, status));

    assertThat(targetRequests).hasValue(0);
    assertThat(originRequests).hasValue(1);
  }

  @Test
  void theEmbeddingClientWithoutKeyFollowsNoRedirect() {
    redirectEverything(307);
    EmbeddingModel client =
        embeddingClientFactory()
            .create(new EmbeddingEndpoint(originBaseUrl(), "bge-m3", null, 2048));

    assertThatThrownBy(() -> client.embed("vertraulicher Text")).isNotNull();

    assertThat(targetRequests).hasValue(0);
  }

  // regression guard for the chat role: a 3xx never reaches an address the target check never saw
  @ParameterizedTest
  @ValueSource(ints = {301, 302, 303, 307, 308})
  void theChatClientFollowsNoRedirect(int status) {
    redirectEverything(status);
    ChatClient client = chatResolver(originBaseUrl(), "cipher").resolveChatClient();

    assertThatThrownBy(() -> client.prompt("vertraulicher Prompt").call().content())
        .satisfies(e -> assertRefusedWithStatus(e, status));

    assertThat(targetRequests).hasValue(0);
    assertThat(originRequests).hasValue(1);
  }

  @Test
  void theChatClientWithoutKeyFollowsNoRedirect() {
    redirectEverything(307);
    ChatClient client = chatResolver(originBaseUrl(), null).resolveChatClient();

    assertThatThrownBy(() -> client.prompt("vertraulicher Prompt").call().content()).isNotNull();

    assertThat(targetRequests).hasValue(0);
  }

  @Test
  void theChatClientWithRequestTimeoutFollowsNoRedirect() {
    redirectEverything(308);
    ChatClient client =
        chatResolver(originBaseUrl(), "cipher").resolveChatClient(Duration.ofSeconds(30));

    assertThatThrownBy(() -> client.prompt("vertraulicher Prompt").call().content()).isNotNull();

    assertThat(targetRequests).hasValue(0);
  }

  @Test
  void aRelativeRedirectOnTheSameServerIsNotFollowedEither() {
    AtomicInteger elsewhereRequests = new AtomicInteger();
    origin.createContext(
        "/elsewhere",
        exchange -> {
          elsewhereRequests.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          send(exchange, 200, EMBEDDING);
        });
    origin.createContext(
        "/v1",
        exchange -> {
          originRequests.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          exchange.getResponseHeaders().add("Location", "/elsewhere/embeddings");
          exchange.sendResponseHeaders(307, -1);
          exchange.close();
        });
    EmbeddingModel client =
        embeddingClientFactory()
            .create(new EmbeddingEndpoint(originBaseUrl(), "bge-m3", "sk-geheim", 2048));

    assertThatThrownBy(() -> client.embed("vertraulicher Text"))
        .satisfies(e -> assertRefusedWithStatus(e, 307));

    assertThat(elsewhereRequests).hasValue(0);
    assertThat(originRequests).hasValue(1);
  }

  @Test
  void aRefusedRedirectNamesTheRedirectButNotTheLocation() {
    redirectEverything(307);
    ChatClient client = chatResolver(originBaseUrl(), "cipher").resolveChatClient();

    assertThatThrownBy(() -> client.prompt("vertraulicher Prompt").call().content())
        .hasStackTraceContaining("307")
        .hasStackTraceContaining("redirect")
        .satisfies(
            e ->
                assertThat(stackTraceOf(e))
                    .doesNotContain(String.valueOf(target.getAddress().getPort())));
  }

  @Test
  void theChatClientStillTakesTheOutboundProxy() throws IOException {
    int port = target.getAddress().getPort();
    try (RecordingHttpProxy proxy =
            new RecordingHttpProxy().route("models.invalid", target.getAddress());
        OutboundProxyRoute route = OutboundProxyRoute.through(proxy.address())) {
      ChatClient client =
          chatResolver("http://models.invalid:" + port + "/v1", "cipher").resolveChatClient();

      String answer = client.prompt("Hallo").call().content();

      assertThat(answer).isEqualTo("Vom Ziel");
      assertThat(proxy.requests())
          .containsExactly("POST http://models.invalid:" + port + "/v1/chat/completions");
    }
  }

  @Test
  void theRequestTimeoutStillEndsAHangingCall() {
    origin.createContext(
        "/v1/chat/completions",
        exchange -> {
          originRequests.incrementAndGet();
          try {
            releaseHangingHandlers.await(60, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        });
    ChatClient client =
        chatResolver(originBaseUrl(), "cipher").resolveChatClient(Duration.ofMillis(500));
    long started = System.nanoTime();

    assertThatThrownBy(() -> client.prompt("Hallo").call().content()).isNotNull();

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(30));
  }

  private void redirectEverything(int status) {
    String location = "http://127.0.0.1:" + target.getAddress().getPort();
    origin.createContext(
        "/",
        exchange -> {
          originRequests.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          exchange
              .getResponseHeaders()
              .add("Location", location + exchange.getRequestURI().getPath());
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          byte[] body =
              "{\"geheim\": \"Inhalt der Weiterleitung\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
  }

  /** The refusal arrives as the SDK's status exception, not as the fail-closed IOException. */
  private static void assertRefusedWithStatus(Throwable thrown, int status) {
    Throwable cause = thrown;
    while (cause != null && !(cause instanceof UnexpectedStatusCodeException)) {
      cause = cause.getCause();
    }
    assertThat(cause)
        .as("UnexpectedStatusCodeException in the cause chain of %s", thrown)
        .isInstanceOf(UnexpectedStatusCodeException.class);
    assertThat(((UnexpectedStatusCodeException) cause).statusCode()).isEqualTo(status);
  }

  private String originBaseUrl() {
    return "http://127.0.0.1:" + origin.getAddress().getPort() + "/v1";
  }

  private static OpenAiEmbeddingClientFactory embeddingClientFactory() {
    return new OpenAiEmbeddingClientFactory(
        Duration.ofSeconds(30), provider(ObservationRegistry.class));
  }

  private static ActiveChatModelResolver chatResolver(String baseUrl, String apiKeyCiphertext) {
    LlmModelRepository repository = mock(LlmModelRepository.class);
    when(repository.findAllByActiveTrue())
        .thenReturn(
            List.of(
                new LlmModel(
                    "Modell R", baseUrl, "model-r", BigDecimal.ZERO, 256, apiKeyCiphertext)));
    SettingsEncryptor encryptor = mock(SettingsEncryptor.class);
    when(encryptor.decrypt(eq("cipher"), any())).thenReturn("sk-geheim");
    return new ActiveChatModelResolver(
        repository,
        encryptor,
        provider(ObservationRegistry.class),
        provider(MeterRegistry.class),
        mock(InvalidationBus.class));
  }

  private static <T> ObjectProvider<T> provider(Class<T> type) {
    return new StaticListableBeanFactory().getBeanProvider(type);
  }

  private static String stackTraceOf(Throwable e) {
    java.io.StringWriter out = new java.io.StringWriter();
    e.printStackTrace(new java.io.PrintWriter(out));
    return out.toString();
  }

  private static void send(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
