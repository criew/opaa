package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opaa.api.types.TextRecognitionConnectionType;
import io.opaa.llm.TextRecognitionClient.TestOutcome;
import io.opaa.llm.TextRecognitionClient.TextRecognitionRejectedException;
import io.opaa.llm.TextRecognitionClient.TextRecognitionUnavailableException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link TextRecognitionClient} against a local {@link HttpServer} standing in for docling-serve
 * and for an OpenAI-compatible vision model: the procedure options and the key header of each
 * connection type, the failure classes of the operation, the outcomes of the connection test, and
 * that no document content and no answer body reach an exception message.
 */
class TextRecognitionClientTest {

  private static final byte[] PDF =
      "%PDF-1.7 Vertrauliche Akte Mustermann".getBytes(StandardCharsets.ISO_8859_1);
  private static final String SECRET_TEXT = "Vertrauliche Akte Mustermann";
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final String DOCLING_SUCCESS =
      "{\"status\":\"success\",\"errors\":[],\"document\":{\"json_content\":{\"texts\":"
          + "[{\"text\":\"OPAA Testseite\"}],\"tables\":[]}}}";
  private static final String VISION_SUCCESS =
      "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"# OPAA Testseite\"}}]}";

  private HttpServer server;
  private String baseUrl;
  private TextRecognitionClient client;

  private record Captured(String method, String body, Map<String, List<String>> headers) {}

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    client = new TextRecognitionClient(new ObjectMapper());
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
    client.close();
  }

  private TextRecognitionEndpoint docling(String apiKey) {
    return new TextRecognitionEndpoint(
        true, TextRecognitionConnectionType.DOCLING_SERVE, baseUrl, null, apiKey);
  }

  private TextRecognitionEndpoint vision(String apiKey) {
    return new TextRecognitionEndpoint(
        true, TextRecognitionConnectionType.OPENAI_VISION, baseUrl + "/v1", "paddle-vl", apiKey);
  }

  private void respond(
      String path, int status, String responseBody, AtomicReference<Captured> captured) {
    server.createContext(
        path,
        exchange -> {
          if (captured != null) {
            captured.set(
                new Captured(
                    exchange.getRequestMethod(),
                    new String(
                        exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1),
                    Map.copyOf(exchange.getRequestHeaders())));
          }
          send(exchange, status, responseBody);
        });
  }

  private static void send(HttpExchange exchange, int status, String responseBody)
      throws IOException {
    byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, response.length == 0 ? -1 : response.length);
    if (response.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(response);
      }
    }
    exchange.close();
  }

  @Test
  void doclingGetsTheProcedureOptionsAndTheKeyAsXApiKey() {
    AtomicReference<Captured> captured = new AtomicReference<>();
    respond("/v1/convert/file", 200, DOCLING_SUCCESS, captured);

    TextRecognitionClient.DoclingConversion conversion =
        client.convertWithDocling(docling("ocr-key"), TIMEOUT, PDF);

    assertThat(conversion.partial()).isFalse();
    assertThat(TextRecognitionClient.doclingText(conversion.document()))
        .isEqualTo("OPAA Testseite");
    Captured request = captured.get();
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.body())
        .contains("name=\"to_formats\"\r\n\r\njson\r\n")
        .contains("name=\"do_ocr\"\r\n\r\ntrue\r\n")
        .contains("name=\"force_ocr\"\r\n\r\ntrue\r\n")
        .contains("name=\"ocr_engine\"\r\n\r\nrapidocr\r\n")
        .contains("name=\"ocr_lang\"\r\n\r\nde\r\n")
        .contains("name=\"do_table_structure\"\r\n\r\ntrue\r\n")
        .contains("name=\"files\"; filename=\"document.pdf\"")
        .contains(SECRET_TEXT);
    assertThat(request.headers().get("X-api-key")).containsExactly("ocr-key");
    assertThat(request.headers()).doesNotContainKey("Authorization");
  }

  @Test
  void theVisionModelGetsThePageAsImageAndTheKeyAsBearer() {
    AtomicReference<Captured> captured = new AtomicReference<>();
    respond("/v1/chat/completions", 200, VISION_SUCCESS, captured);

    String text =
        client.recognizePageWithVision(
            vision("vision-key"), TIMEOUT, new byte[] {1, 2, 3}, "image/png");

    assertThat(text).isEqualTo("# OPAA Testseite");
    Captured request = captured.get();
    assertThat(request.body())
        .contains("\"model\":\"paddle-vl\"")
        .contains("data:image/png;base64,AQID")
        .contains(TextRecognitionVisionPrompt.TEXT);
    assertThat(request.headers().get("Authorization")).containsExactly("Bearer vision-key");
    assertThat(request.headers()).doesNotContainKey("X-api-key");
  }

  @Test
  void withoutAKeyNoHeaderIsSent() {
    AtomicReference<Captured> captured = new AtomicReference<>();
    respond("/v1/convert/file", 200, DOCLING_SUCCESS, captured);

    client.convertWithDocling(docling(""), TIMEOUT, PDF);

    assertThat(captured.get().headers()).doesNotContainKeys("X-api-key", "Authorization");
  }

  @Test
  void aServiceFaultIsUnavailableAndADocumentRefusalIsRejected() {
    respond("/v1/convert/file", 503, SECRET_TEXT, null);
    assertThatThrownBy(() -> client.convertWithDocling(docling("k"), TIMEOUT, PDF))
        .isInstanceOf(TextRecognitionUnavailableException.class)
        .hasMessageNotContaining(SECRET_TEXT)
        .satisfies(e -> assertThat(((TextRecognitionUnavailableException) e).answered()).isTrue());

    server.removeContext("/v1/convert/file");
    respond("/v1/convert/file", 401, "", null);
    assertThatThrownBy(() -> client.convertWithDocling(docling("k"), TIMEOUT, PDF))
        .isInstanceOf(TextRecognitionUnavailableException.class)
        .satisfies(
            e -> assertThat(((TextRecognitionUnavailableException) e).keyRejected()).isTrue());

    server.removeContext("/v1/convert/file");
    respond("/v1/convert/file", 429, "", null);
    assertThatThrownBy(() -> client.convertWithDocling(docling("k"), TIMEOUT, PDF))
        .isInstanceOf(TextRecognitionUnavailableException.class);

    server.removeContext("/v1/convert/file");
    respond("/v1/convert/file", 422, SECRET_TEXT, null);
    assertThatThrownBy(() -> client.convertWithDocling(docling("k"), TIMEOUT, PDF))
        .isInstanceOf(TextRecognitionRejectedException.class)
        .hasMessageNotContaining(SECRET_TEXT);

    server.removeContext("/v1/convert/file");
    respond(
        "/v1/convert/file",
        200,
        "{\"status\":\"failure\",\"errors\":[{\"error_message\":\"" + SECRET_TEXT + "\"}]}",
        null);
    assertThatThrownBy(() -> client.convertWithDocling(docling("k"), TIMEOUT, PDF))
        .isInstanceOf(TextRecognitionRejectedException.class)
        .hasMessageNotContaining(SECRET_TEXT);
  }

  @Test
  void anUnreachableServiceIsUnavailableWithoutAnAnswer() throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    TextRecognitionEndpoint endpoint =
        new TextRecognitionEndpoint(
            true,
            TextRecognitionConnectionType.DOCLING_SERVE,
            "http://127.0.0.1:" + closedPort,
            null,
            "k");

    assertThatThrownBy(() -> client.convertWithDocling(endpoint, TIMEOUT, PDF))
        .isInstanceOf(TextRecognitionUnavailableException.class)
        .satisfies(e -> assertThat(((TextRecognitionUnavailableException) e).answered()).isFalse());
    assertThat(client.test(endpoint, TIMEOUT).outcome()).isEqualTo(TestOutcome.UNREACHABLE);
  }

  @Test
  void aRedirectIsNeverFollowedSoTheKeyStaysWithItsEndpoint() {
    AtomicInteger followed = new AtomicInteger();
    server.createContext(
        "/elsewhere",
        exchange -> {
          followed.incrementAndGet();
          send(exchange, 200, DOCLING_SUCCESS);
        });
    server.createContext(
        "/v1/convert/file",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          exchange.getResponseHeaders().add("Location", baseUrl + "/elsewhere");
          send(exchange, 307, "");
        });

    assertThatThrownBy(() -> client.convertWithDocling(docling("k"), TIMEOUT, PDF))
        .isInstanceOf(TextRecognitionUnavailableException.class)
        .hasMessageContaining("redirect");
    assertThat(followed).hasValue(0);
  }

  @Test
  void theDoclingTestTellsReadyFromKeyRejectedEngineMissingAndNoText() {
    respond("/health", 200, "{\"status\":\"ok\"}", null);
    respond("/v1/convert/file", 200, DOCLING_SUCCESS, null);
    assertThat(client.test(docling("k"), TIMEOUT).outcome()).isEqualTo(TestOutcome.READY);

    server.removeContext("/v1/convert/file");
    respond("/v1/convert/file", 401, "", null);
    assertThat(client.test(docling("wrong"), TIMEOUT).outcome())
        .isEqualTo(TestOutcome.KEY_REJECTED);

    server.removeContext("/v1/convert/file");
    respond("/v1/convert/file", 422, "{\"detail\":\"ocr_engine rapidocr unknown\"}", null);
    assertThat(client.test(docling("k"), TIMEOUT).outcome()).isEqualTo(TestOutcome.ENGINE_MISSING);

    server.removeContext("/v1/convert/file");
    respond("/v1/convert/file", 200, "{\"status\":\"failure\",\"errors\":[]}", null);
    assertThat(client.test(docling("k"), TIMEOUT).outcome()).isEqualTo(TestOutcome.ENGINE_MISSING);

    server.removeContext("/v1/convert/file");
    respond(
        "/v1/convert/file",
        200,
        "{\"status\":\"success\",\"document\":{\"json_content\":{\"texts\":[],\"tables\":[]}}}",
        null);
    assertThat(client.test(docling("k"), TIMEOUT).outcome()).isEqualTo(TestOutcome.NO_TEXT);
  }

  @Test
  void theDoclingTestSendsTheShippedTestPageWithTheOperationsOptions() {
    respond("/health", 200, "{\"status\":\"ok\"}", null);
    AtomicReference<Captured> captured = new AtomicReference<>();
    respond("/v1/convert/file", 200, DOCLING_SUCCESS, captured);

    client.test(docling("k"), TIMEOUT);

    assertThat(captured.get().body())
        .contains("name=\"ocr_engine\"\r\n\r\nrapidocr\r\n")
        .contains("name=\"force_ocr\"\r\n\r\ntrue\r\n")
        .contains("Content-Type: application/pdf\r\n\r\n%PDF");
    assertThat(captured.get().headers().get("X-api-key")).containsExactly("k");
  }

  @Test
  void theVisionTestTellsReadyFromKeyRejectedMissingModelAndNoText() {
    respond("/v1/models", 200, "{\"data\":[{\"id\":\"paddle-vl\"}]}", null);
    respond("/v1/chat/completions", 200, VISION_SUCCESS, null);
    assertThat(client.test(vision("k"), TIMEOUT).outcome()).isEqualTo(TestOutcome.READY);

    server.removeContext("/v1/models");
    respond("/v1/models", 401, "", null);
    assertThat(client.test(vision("wrong"), TIMEOUT).outcome()).isEqualTo(TestOutcome.KEY_REJECTED);

    server.removeContext("/v1/models");
    respond("/v1/models", 200, "{\"data\":[{\"id\":\"another-model\"}]}", null);
    assertThat(client.test(vision("k"), TIMEOUT).outcome()).isEqualTo(TestOutcome.ENGINE_MISSING);

    server.removeContext("/v1/models");
    respond("/v1/models", 200, "{\"data\":[{\"id\":\"paddle-vl\"}]}", null);
    server.removeContext("/v1/chat/completions");
    respond(
        "/v1/chat/completions",
        200,
        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"  \"}}]}",
        null);
    assertThat(client.test(vision("k"), TIMEOUT).outcome()).isEqualTo(TestOutcome.NO_TEXT);
  }

  @Test
  void theProbesAskHealthOrTheModelListOnly() {
    AtomicReference<Captured> health = new AtomicReference<>();
    respond("/health", 200, "{\"status\":\"ok\"}", health);
    AtomicReference<Captured> models = new AtomicReference<>();
    respond("/v1/models", 200, "{\"data\":[]}", models);

    assertThat(client.probe(docling("k"), TIMEOUT)).isNull();
    assertThat(client.probe(vision("k"), TIMEOUT)).isNull();
    assertThat(health.get().method()).isEqualTo("GET");
    assertThat(models.get().headers().get("Authorization")).containsExactly("Bearer k");
  }

  @Test
  void aWrongKeyIsCaughtByTheDoclingProbeThoughHealthNeedsNone() {
    respond("/health", 200, "{\"status\":\"ok\"}", null);
    AtomicReference<Captured> poll = new AtomicReference<>();
    respond(TextRecognitionClient.KEY_CHECK_PATH, 401, "", poll);
    AtomicReference<Captured> conversion = new AtomicReference<>();
    respond("/v1/convert/file", 200, DOCLING_SUCCESS, conversion);

    TextRecognitionClient.ProbeFailure failure = client.probe(docling("wrong"), TIMEOUT);

    assertThat(failure).isNotNull();
    assertThat(failure.keyRejected()).isTrue();
    assertThat(poll.get().method()).isEqualTo("GET");
    assertThat(poll.get().headers().get("X-api-key")).containsExactly("wrong");
    assertThat(client.test(docling("wrong"), TIMEOUT).outcome())
        .isEqualTo(TestOutcome.KEY_REJECTED);
    assertThat(conversion.get()).isNull();

    server.removeContext(TextRecognitionClient.KEY_CHECK_PATH);
    respond(TextRecognitionClient.KEY_CHECK_PATH, 404, "{\"detail\":\"Task not found.\"}", null);
    assertThat(client.probe(docling("k"), TIMEOUT)).isNull();
  }

  @Test
  void theTestNamesTheStageThatFailed() throws IOException {
    respond("/health", 200, "{\"status\":\"ok\"}", null);
    respond("/v1/convert/file", 200, DOCLING_SUCCESS, null);
    assertThat(client.test(docling("k"), TIMEOUT).failedStage()).isNull();

    server.removeContext("/v1/convert/file");
    respond("/v1/convert/file", 404, "", null);
    TextRecognitionClient.TestResult missingRoute = client.test(docling("k"), TIMEOUT);
    assertThat(missingRoute.outcome()).isEqualTo(TestOutcome.UNREACHABLE);
    assertThat(missingRoute.failedStage()).isEqualTo(TextRecognitionClient.FailedStage.RECOGNITION);

    server.removeContext("/v1/convert/file");
    respond("/v1/convert/file", 200, "not json", null);
    assertThat(client.test(docling("k"), TIMEOUT).failedStage())
        .isEqualTo(TextRecognitionClient.FailedStage.RECOGNITION);

    server.removeContext("/v1/convert/file");
    respond("/v1/convert/file", 401, "", null);
    assertThat(client.test(docling("k"), TIMEOUT).failedStage())
        .isEqualTo(TextRecognitionClient.FailedStage.AUTHENTICATION);

    respond(TextRecognitionClient.KEY_CHECK_PATH, 401, "", null);
    assertThat(client.test(docling("wrong"), TIMEOUT).failedStage())
        .isEqualTo(TextRecognitionClient.FailedStage.AUTHENTICATION);

    server.removeContext("/health");
    respond("/health", 302, "", null);
    assertThat(client.test(docling("k"), TIMEOUT).failedStage())
        .isEqualTo(TextRecognitionClient.FailedStage.ADDRESS);

    ServerSocket free = new ServerSocket(0);
    int port = free.getLocalPort();
    free.close();
    TextRecognitionClient.TestResult gone =
        client.test(
            new TextRecognitionEndpoint(
                true,
                TextRecognitionConnectionType.DOCLING_SERVE,
                "http://127.0.0.1:" + port,
                null,
                "k"),
            TIMEOUT);
    assertThat(gone.outcome()).isEqualTo(TestOutcome.UNREACHABLE);
    assertThat(gone.failedStage()).isEqualTo(TextRecognitionClient.FailedStage.ADDRESS);
  }

  @Test
  void aTimeoutWhileReadingTheTestPageIsARecognitionFailure() {
    respond("/health", 200, "{\"status\":\"ok\"}", null);
    server.createContext(
        "/v1/convert/file",
        exchange -> {
          try {
            Thread.sleep(3_000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          send(exchange, 200, DOCLING_SUCCESS);
        });

    TextRecognitionClient.TestResult result = client.test(docling("k"), Duration.ofMillis(800));

    assertThat(result.outcome()).isEqualTo(TestOutcome.UNREACHABLE);
    assertThat(result.timedOut()).isTrue();
    assertThat(result.failedStage()).isEqualTo(TextRecognitionClient.FailedStage.RECOGNITION);
  }
}
