package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.opaa.llm.TranscriptionClient.ProbeFailure;
import io.opaa.llm.TranscriptionClient.TranscriptionUnavailableException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link TranscriptionClient} against a real local {@link HttpServer} standing in for an
 * OpenAI-compatible transcription endpoint (speaches, vLLM, LiteLLM, OpenRouter): request shape,
 * key handling, and that neither audio nor recognised text nor the endpoint's answer ever reaches
 * an exception message.
 */
class TranscriptionClientTest {

  private static final byte[] AUDIO = "fake-opus-bytes".getBytes(StandardCharsets.US_ASCII);
  private static final String SECRET_TEXT = "Vertrauliche Frage zum Bauantrag Mustermann";

  private HttpServer server;
  private String baseUrl;
  private TranscriptionClient client;
  private final CountDownLatch release = new CountDownLatch(1);

  /** An endpoint with the budget of one call. */
  private record Call(TranscriptionEndpoint endpoint, Duration timeout) {}

  private String transcribe(Call call, byte[] audio, TranscriptionAudioFormat format) {
    return client.transcribe(call.endpoint(), call.timeout(), audio, format);
  }

  private ProbeFailure probe(Call call) {
    return client.probe(call.endpoint(), call.timeout());
  }

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    client = new TranscriptionClient(new ObjectMapper());
  }

  @AfterEach
  void tearDown() {
    release.countDown();
    server.stop(0);
    client.close();
  }

  private Call properties(String apiKey, String language, Duration timeout) {
    return new Call(
        new TranscriptionEndpoint(true, baseUrl, "whisper-large-v3-turbo", apiKey, language),
        timeout);
  }

  private Call properties() {
    return properties("secret-key", "de", Duration.ofSeconds(5));
  }

  private record Captured(
      String method, String body, String contentType, Map<String, List<String>> headers) {}

  private void respond(
      String path, int status, String responseBody, AtomicReference<Captured> captured) {
    server.createContext(
        path,
        exchange -> {
          captured.set(
              new Captured(
                  exchange.getRequestMethod(),
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1),
                  exchange.getRequestHeaders().getFirst("Content-Type"),
                  Map.copyOf(exchange.getRequestHeaders())));
          byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, response.length == 0 ? -1 : response.length);
          if (response.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
              out.write(response);
            }
          }
          exchange.close();
        });
  }

  @Test
  void sendsTheOpenAiMultipartFieldsAndReadsTheText() {
    AtomicReference<Captured> captured = new AtomicReference<>();
    respond("/v1/audio/transcriptions", 200, "{\"text\":\"  Hallo Welt \"}", captured);

    String text = transcribe(properties(), AUDIO, TranscriptionAudioFormat.WEBM);

    assertThat(text).isEqualTo("Hallo Welt");
    Captured request = captured.get();
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.contentType()).startsWith("multipart/form-data; boundary=");
    assertThat(request.body())
        .contains("name=\"model\"\r\n\r\nwhisper-large-v3-turbo\r\n")
        .contains("name=\"language\"\r\n\r\nde\r\n")
        .contains("name=\"response_format\"\r\n\r\njson\r\n")
        .contains("name=\"file\"; filename=\"recording.webm\"\r\nContent-Type: audio/webm")
        .contains("fake-opus-bytes");
    assertThat(request.headers().get("Authorization")).containsExactly("Bearer secret-key");
  }

  /** The endpoint learns nothing about the person: no user field, no header naming anyone. */
  @Test
  void sendsNoPersonIdentifier() {
    AtomicReference<Captured> captured = new AtomicReference<>();
    respond("/v1/audio/transcriptions", 200, "{\"text\":\"x\"}", captured);

    transcribe(properties(), AUDIO, TranscriptionAudioFormat.MP4);

    Captured request = captured.get();
    assertThat(request.body()).doesNotContain("name=\"user\"").contains("recording.mp4");
    assertThat(request.headers().keySet())
        .map(String::toLowerCase)
        .allMatch(
            name ->
                List.of("authorization", "content-type", "content-length", "host", "user-agent")
                    .contains(name));
  }

  @Test
  void omitsLanguageWhenNoneIsConfiguredAndAuthorizationWithoutKey() {
    AtomicReference<Captured> captured = new AtomicReference<>();
    respond("/v1/audio/transcriptions", 200, "{\"text\":\"x\"}", captured);

    transcribe(properties("", "", Duration.ofSeconds(5)), AUDIO, TranscriptionAudioFormat.WEBM);

    assertThat(captured.get().body()).doesNotContain("name=\"language\"");
    assertThat(captured.get().headers()).doesNotContainKey("Authorization");
  }

  /** Regression guard for the Personalrat condition: no content in any exception message. */
  @Test
  void anErrorAnswerIsReportedWithoutItsBody() {
    respond(
        "/v1/audio/transcriptions",
        500,
        "{\"error\":\"could not decode " + SECRET_TEXT + "\"}",
        new AtomicReference<>());

    assertThatThrownBy(() -> transcribe(properties(), AUDIO, TranscriptionAudioFormat.WEBM))
        .isInstanceOf(TranscriptionUnavailableException.class)
        .satisfies(e -> assertThat(messagesOf(e)).doesNotContain(SECRET_TEXT, "fake-opus"))
        .satisfies(e -> assertThat(((TranscriptionUnavailableException) e).answered()).isTrue())
        .hasMessage("HTTP 500 from the transcription endpoint");
  }

  @Test
  void anUnreadableAnswerIsReportedWithoutItsBodyOrTheParserCause() {
    respond(
        "/v1/audio/transcriptions", 200, "{\"text\": \"" + SECRET_TEXT, new AtomicReference<>());

    assertThatThrownBy(() -> transcribe(properties(), AUDIO, TranscriptionAudioFormat.WEBM))
        .isInstanceOf(TranscriptionUnavailableException.class)
        .hasNoCause()
        .satisfies(e -> assertThat(messagesOf(e)).doesNotContain(SECRET_TEXT));
  }

  @Test
  void anAnswerWithoutTextIsAFailure() {
    respond("/v1/audio/transcriptions", 200, "{\"segments\":[]}", new AtomicReference<>());

    assertThatThrownBy(() -> transcribe(properties(), AUDIO, TranscriptionAudioFormat.WEBM))
        .isInstanceOf(TranscriptionUnavailableException.class)
        .satisfies(e -> assertThat(((TranscriptionUnavailableException) e).answered()).isTrue())
        .hasMessage("endpoint response contains no text");
  }

  @Test
  void aStalledEndpointRunsIntoTheTimeout() {
    server.createContext(
        "/v1/audio/transcriptions",
        exchange -> {
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        });

    assertThatThrownBy(
            () ->
                transcribe(
                    properties("k", "de", Duration.ofMillis(300)),
                    AUDIO,
                    TranscriptionAudioFormat.WEBM))
        .isInstanceOf(TranscriptionUnavailableException.class)
        .satisfies(e -> assertThat(((TranscriptionUnavailableException) e).timedOut()).isTrue())
        .satisfies(e -> assertThat(((TranscriptionUnavailableException) e).answered()).isFalse());
  }

  @Test
  void theProbeAsksForTheModelListWithoutAudio() {
    AtomicReference<Captured> captured = new AtomicReference<>();
    respond("/v1/models", 200, "{\"data\":[]}", captured);

    ProbeFailure failure = probe(properties());

    assertThat(failure).isNull();
    assertThat(captured.get().method()).isEqualTo("GET");
    assertThat(captured.get().body()).isEmpty();
    assertThat(captured.get().headers().get("Authorization")).containsExactly("Bearer secret-key");
  }

  @Test
  void aRefusedProbeNamesTheStatus() {
    respond("/v1/models", 401, "", new AtomicReference<>());

    ProbeFailure failure = probe(properties());

    assertThat(failure).isNotNull();
    assertThat(failure.message()).contains("401");
    assertThat(failure.timedOut()).isFalse();
  }

  @Test
  void aProbeAgainstAClosedPortIsAFailure() {
    server.stop(0);

    assertThat(probe(properties())).isNotNull();
  }

  private static String messagesOf(Throwable e) {
    StringBuilder all = new StringBuilder();
    for (Throwable current = e; current != null; current = current.getCause()) {
      all.append(current.getMessage()).append('\n');
    }
    return all.toString();
  }
}
