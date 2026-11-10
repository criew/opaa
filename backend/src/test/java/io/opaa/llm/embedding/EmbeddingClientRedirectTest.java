package io.opaa.llm.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openai.errors.UnexpectedStatusCodeException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The runtime client of the embedding role, built exactly as {@link OpenAiEmbeddingClientFactory}
 * builds it, follows no redirect: the redirect target is never contacted, with or without an access
 * key. The chat client's counterpart is {@code io.opaa.llm.ModelClientRedirectTest}.
 */
class EmbeddingClientRedirectTest {

  private static final String EMBEDDING =
      """
      {"object": "list", "model": "bge-m3",
       "data": [{"object": "embedding", "index": 0, "embedding": [0.1, 0.2, 0.3]}],
       "usage": {"prompt_tokens": 1, "total_tokens": 1}}
      """;

  private final ExecutorService serverThreads = Executors.newCachedThreadPool();
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
          send(exchange, 200, EMBEDDING);
        });
    target.start();
  }

  @AfterEach
  void stopServers() {
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
        Duration.ofSeconds(30),
        new StaticListableBeanFactory().getBeanProvider(ObservationRegistry.class));
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
