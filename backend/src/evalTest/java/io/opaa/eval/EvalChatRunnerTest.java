package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Docker-free tests for {@link EvalChatRunner} against a fake Ollama endpoint. */
class EvalChatRunnerTest {

  private static final String MODEL = "qwen2.5:1.5b-instruct";

  private final List<String> unloadBodies = new CopyOnWriteArrayList<>();
  private final AtomicInteger psCalls = new AtomicInteger();
  private HttpServer server;

  @AfterEach
  void stop() {
    EvalChatRunner.clear();
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void unloadRequestsKeepAliveZeroAndWaitsUntilTheModelIsGone() throws IOException {
    String endpoint = fakeOllama(2);

    EvalChatRunner.unload(endpoint, MODEL);

    assertThat(unloadBodies).hasSize(1);
    JsonNode body = JsonMapper.builder().build().readTree(unloadBodies.get(0));
    assertThat(body.get("model").asString()).isEqualTo(MODEL);
    assertThat(body.get("keep_alive").asInt()).isZero();
    assertThat(psCalls.get())
        .as("polled until /api/ps stopped listing the model, not just once")
        .isEqualTo(3);
  }

  @Test
  void startFreshUnloadsTheRegisteredRunnerAndNothingWithoutOne() throws IOException {
    String endpoint = fakeOllama(0);

    EvalChatRunner.startFresh();
    assertThat(unloadBodies).isEmpty();

    EvalChatRunner.register(endpoint, MODEL);
    EvalChatRunner.startFresh();
    assertThat(unloadBodies).hasSize(1);

    EvalChatRunner.clear();
    EvalChatRunner.startFresh();
    assertThat(unloadBodies).hasSize(1);
  }

  @Test
  void aRefusedUnloadFailsInsteadOfMeasuringOnTheWarmRunner() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/generate", exchange -> respond(exchange, 404, "{}"));
    server.start();

    assertThatThrownBy(() -> EvalChatRunner.unload(endpoint(), MODEL))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HTTP 404");
  }

  /** A fake endpoint whose /api/ps lists {@link #MODEL} for the first {@code loadedPolls} calls. */
  private String fakeOllama(int loadedPolls) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/generate",
        exchange -> {
          unloadBodies.add(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          respond(exchange, 200, "{\"done\":true,\"done_reason\":\"unload\"}");
        });
    server.createContext(
        "/api/ps",
        exchange ->
            respond(
                exchange,
                200,
                psCalls.incrementAndGet() <= loadedPolls
                    ? "{\"models\":[{\"name\":\"" + MODEL + "\",\"model\":\"" + MODEL + "\"}]}"
                    : "{\"models\":[]}"));
    server.start();
    return endpoint();
  }

  private String endpoint() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
