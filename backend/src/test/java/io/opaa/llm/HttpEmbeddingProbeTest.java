package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The probe embedding sends one request to {@code {baseUrl}/embeddings} with the key, reads the
 * vector size, follows no redirect and reads no oversized answer.
 */
class HttpEmbeddingProbeTest {

  private static final List<String> ONE_TEXT = List.of("OPAA Probeeinbettung");

  private final HttpEmbeddingProbe probe = new HttpEmbeddingProbe(JsonMapper.builder().build());
  private HttpServer server;
  private String baseUrl;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void reportsTheVectorSizeAndSendsModelAndKey() {
    AtomicReference<String> request = new AtomicReference<>();
    AtomicReference<String> authorization = new AtomicReference<>();
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          send(exchange, 200, "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}");
        });

    EmbeddingModelService.ProbeOutcome outcome =
        probe.probe(new EmbeddingEndpoint(baseUrl, "bge-m3", "sk-geheim", 2048), ONE_TEXT);

    assertThat(outcome.succeeded()).isTrue();
    assertThat(outcome.dimensions()).isEqualTo(3);
    assertThat(request.get()).contains("\"model\":\"bge-m3\"");
    assertThat(authorization.get()).isEqualTo("Bearer sk-geheim");
  }

  // regression guard: the probe follows no redirect to an address the target check never saw
  @Test
  void aRedirectIsNotFollowed() throws IOException {
    HttpServer elsewhere = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicBoolean elsewhereContacted = new AtomicBoolean(false);
    elsewhere.createContext(
        "/",
        exchange -> {
          elsewhereContacted.set(true);
          send(exchange, 200, "{\"data\":[{\"index\":0,\"embedding\":[0.1]}]}");
        });
    elsewhere.start();
    try {
      String target = "http://127.0.0.1:" + elsewhere.getAddress().getPort() + "/v1/embeddings";
      server.createContext(
          "/v1/embeddings",
          exchange -> {
            exchange.getResponseHeaders().add("Location", target);
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
          });

      EmbeddingModelService.ProbeOutcome outcome =
          probe.probe(new EmbeddingEndpoint(baseUrl, "bge-m3", null, 2048), ONE_TEXT);

      assertThat(outcome.succeeded()).isFalse();
      assertThat(elsewhereContacted.get()).isFalse();
    } finally {
      elsewhere.stop(0);
    }
  }

  @Test
  void aRefusedKeyAndAnOversizedAnswerFail() {
    server.createContext("/v1/embeddings", exchange -> send(exchange, 401, "{}"));

    assertThat(
            probe
                .probe(new EmbeddingEndpoint(baseUrl, "bge-m3", "sk-falsch", 2048), ONE_TEXT)
                .message())
        .contains("HTTP 401");

    server.removeContext("/v1/embeddings");
    server.createContext(
        "/v1/embeddings",
        exchange -> send(exchange, 200, "x".repeat(HttpEmbeddingProbe.MAX_RESPONSE_BYTES + 10)));

    assertThat(
            probe.probe(new EmbeddingEndpoint(baseUrl, "bge-m3", null, 2048), ONE_TEXT).succeeded())
        .isFalse();
  }

  private static void send(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getRequestBody().readAllBytes();
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  // regression guard: a server that trickles its answer cannot hold the probe beyond its deadline
  @Test
  void aTricklingAnswerEndsAtTheDeadline() {
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream out = exchange.getResponseBody()) {
            for (int i = 0; i < 60; i++) {
              out.write(' ');
              out.flush();
              Thread.sleep(1000);
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch (IOException e) {
            // the probe gave up and closed the connection
          }
        });
    long start = System.nanoTime();

    EmbeddingModelService.ProbeOutcome outcome =
        probe.probe(new EmbeddingEndpoint(baseUrl, "bge-m3", null, 2048), ONE_TEXT);

    assertThat(outcome.succeeded()).isFalse();
    assertThat(java.time.Duration.ofNanos(System.nanoTime() - start))
        .isLessThan(java.time.Duration.ofSeconds(HttpEmbeddingProbe.TIMEOUT_SECONDS + 5));
  }

  @Test
  void aKeyWithALineBreakIsRefusedBeforeAnyRequest() {
    AtomicBoolean contacted = new AtomicBoolean(false);
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          contacted.set(true);
          send(exchange, 200, "{}");
        });

    EmbeddingModelService.ProbeOutcome outcome =
        probe.probe(
            new EmbeddingEndpoint(baseUrl, "bge-m3", "sk-eins\r\nX-Evil: 1", 2048), ONE_TEXT);

    assertThat(outcome.succeeded()).isFalse();
    assertThat(outcome.message()).contains("unzulässige Zeichen");
    assertThat(contacted.get()).isFalse();
  }
}
