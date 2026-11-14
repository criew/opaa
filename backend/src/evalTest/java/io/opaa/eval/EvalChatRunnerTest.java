package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opaa.security.SettingsEncryptor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
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

  /**
   * Regression guard for ADR-0012, Entscheidung 74: without registration {@link
   * EvalChatRunner#startFresh} is a silent no-op, so the pinned in-container model must register
   * and every endpoint this run does not own must leave nothing registered.
   */
  @Test
  void onlyThePinnedModelInTheHarnessContainerIsRegistered() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    SettingsEncryptor encryptor = Mockito.mock(SettingsEncryptor.class);
    String container = "http://localhost:32768";

    withProperties(
        Map.of(),
        () -> {
          EvalChatModel.installAsSystemwideActiveModel(jdbc, container);
          assertThat(EvalChatRunner.registered())
              .contains(new EvalChatRunner.Target(container, EvalChatModel.MODEL));
          EvalChatRunner.clear();
          EvalChatModel.installAsSystemwideActiveModel(jdbc, container, encryptor);
          assertThat(EvalChatRunner.registered())
              .contains(new EvalChatRunner.Target(container, EvalChatModel.MODEL));
        });

    withProperties(
        Map.of(EvalOllamaEndpoint.BASE_URL_PROPERTY, "http://localhost:11434"),
        () -> {
          EvalChatRunner.register(container, EvalChatModel.MODEL);
          EvalChatModel.installAsSystemwideActiveModel(jdbc, "http://localhost:11434");
          assertThat(EvalChatRunner.registered()).as("developer's host Ollama").isEmpty();
          EvalChatRunner.register(container, EvalChatModel.MODEL);
          EvalChatModel.installAsSystemwideActiveModel(jdbc, "http://localhost:11434", encryptor);
          assertThat(EvalChatRunner.registered()).as("developer's host Ollama").isEmpty();
        });

    withProperties(
        Map.of(
            "opaa.eval.chatBaseUrl", "http://ollama.intern:11434/v1", "opaa.eval.chatModel", "m"),
        () -> {
          EvalChatRunner.register(container, EvalChatModel.MODEL);
          EvalChatModel.installAsSystemwideActiveModel(jdbc, container, encryptor);
          assertThat(EvalChatRunner.registered()).as("external chat model").isEmpty();
        });
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

  private static final List<String> PROPERTIES =
      List.of(
          EvalOllamaEndpoint.BASE_URL_PROPERTY,
          "opaa.eval.chatBaseUrl",
          "opaa.eval.chatModel",
          "opaa.eval.chatTemperature");

  /**
   * Runs {@code body} with exactly {@code values} among the eval properties, then restores them.
   */
  private static void withProperties(Map<String, String> values, Runnable body) {
    Map<String, String> saved = new HashMap<>();
    PROPERTIES.forEach(key -> saved.put(key, System.getProperty(key)));
    try {
      PROPERTIES.forEach(System::clearProperty);
      values.forEach(System::setProperty);
      body.run();
    } finally {
      saved.forEach(
          (key, value) -> {
            if (value == null) {
              System.clearProperty(key);
            } else {
              System.setProperty(key, value);
            }
          });
    }
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
