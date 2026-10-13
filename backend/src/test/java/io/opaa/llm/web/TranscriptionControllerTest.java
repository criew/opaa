package io.opaa.llm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import io.opaa.api.GlobalExceptionHandler;
import io.opaa.api.RequestLoggingFilter;
import io.opaa.common.FeatureFlags;
import io.opaa.knowledge.UploadProperties;
import io.opaa.llm.TranscriptionClient;
import io.opaa.llm.TranscriptionModelRole;
import io.opaa.llm.TranscriptionProperties;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

/**
 * The dictation endpoint end to end against a fake transcription endpoint: the real role, the real
 * client, the shared error handling and the request log. The recording arrives as a raw audio body
 * - never multipart, so the container never buffers it to disk - and neither the recording nor the
 * recognised text nor the endpoint's answer appears in any log line, on success or failure.
 */
class TranscriptionControllerTest {

  private static final String RECOGNISED = "Welche Frist gilt fuer den Antrag von Erika Musterfrau";
  private static final String AUDIO_MARKER = "AUDIO-MARKER-7f3a";
  private static final byte[] AUDIO = AUDIO_MARKER.getBytes(StandardCharsets.US_ASCII);
  private static final int MAX_AUDIO_BYTES = 1024;

  private HttpServer upstream;
  private final AtomicInteger upstreamStatus = new AtomicInteger(200);
  private volatile String upstreamBody = "{\"text\":\"" + RECOGNISED + "\"}";
  private final AtomicInteger transcriptionCalls = new AtomicInteger();
  private TranscriptionClient client;
  private ListAppender<ILoggingEvent> logs;
  private Logger rootLogger;

  @BeforeEach
  void setUp() throws IOException {
    upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.createContext("/v1/models", exchange -> answer(exchange, 200, "{\"data\":[]}"));
    upstream.createContext(
        "/v1/audio/transcriptions",
        exchange -> {
          transcriptionCalls.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          answer(exchange, upstreamStatus.get(), upstreamBody);
        });
    upstream.start();
    client = new TranscriptionClient(new ObjectMapper());

    logs = new ListAppender<>();
    logs.start();
    rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    rootLogger.addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    rootLogger.detachAppender(logs);
    upstream.stop(0);
  }

  private static void answer(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private MockMvc mockMvc(boolean enabled) {
    TranscriptionProperties properties =
        new TranscriptionProperties(
            enabled,
            "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1",
            "whisper",
            "",
            "de",
            MAX_AUDIO_BYTES,
            Duration.ofSeconds(5));
    TranscriptionModelRole role =
        new TranscriptionModelRole(properties, client, new FeatureFlags(new MockEnvironment()));
    role.refresh();
    return MockMvcBuilders.standaloneSetup(new TranscriptionController(role))
        .setControllerAdvice(
            new GlobalExceptionHandler(new UploadProperties(null, null, 52_428_800L, null, 0, 0)))
        .addFilters(new RequestLoggingFilter())
        .build();
  }

  @Test
  void availabilityFollowsTheRoleAndNeverOffersLiveText() throws Exception {
    mockMvc(true)
        .perform(get("/api/v1/transcriptions/availability"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(true))
        .andExpect(jsonPath("$.realtimeAvailable").value(false));
    mockMvc(false)
        .perform(get("/api/v1/transcriptions/availability"))
        .andExpect(jsonPath("$.available").value(false));
  }

  @Test
  void aRecordingIsAnsweredAsTextAndNothingOfItIsLogged() throws Exception {
    mockMvc(true)
        .perform(
            post("/api/v1/transcriptions").contentType("audio/webm;codecs=opus").content(AUDIO))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.text").value(RECOGNISED));

    assertLogsCarryNoContent();
    assertThat(formattedLines())
        .anyMatch(line -> line.startsWith("POST /api/v1/transcriptions 200"));
  }

  @Test
  void anUpstreamFailureIs502AndNeitherAudioNorTheUpstreamAnswerIsLogged() throws Exception {
    upstreamStatus.set(500);
    upstreamBody = "{\"error\":\"decoder failed on: " + RECOGNISED + "\"}";

    mockMvc(true)
        .perform(post("/api/v1/transcriptions").contentType("audio/webm").content(AUDIO))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error").value(TranscriptionController.UPSTREAM_FAILED_MESSAGE));

    assertLogsCarryNoContent();
  }

  @Test
  void anUnreadableUpstreamAnswerIs502WithoutLoggingIt() throws Exception {
    upstreamBody = "{\"text\":\"" + RECOGNISED;

    mockMvc(true)
        .perform(post("/api/v1/transcriptions").contentType("audio/webm").content(AUDIO))
        .andExpect(status().isBadGateway());

    assertLogsCarryNoContent();
  }

  @Test
  void aSwitchedOffRoleIs503AndSendsNothing() throws Exception {
    mockMvc(false)
        .perform(post("/api/v1/transcriptions").contentType("audio/webm").content(AUDIO))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value(TranscriptionController.UNAVAILABLE_MESSAGE));

    assertThat(transcriptionCalls).hasValue(0);
  }

  @Test
  void aRecordingAboveTheBoundIs413AndSendsNothing() throws Exception {
    mockMvc(true)
        .perform(
            post("/api/v1/transcriptions")
                .contentType("audio/webm")
                .content(new byte[MAX_AUDIO_BYTES + 1]))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(
            jsonPath("$.error").value("Die Aufnahme ist zu groß. Erlaubt sind höchstens 1 MB."));

    assertThat(transcriptionCalls).hasValue(0);
    assertLogsCarryNoContent();
  }

  @Test
  void anEmptyRecordingAndAnUnknownFormatAre400() throws Exception {
    MockMvc mockMvc = mockMvc(true);
    mockMvc
        .perform(post("/api/v1/transcriptions").contentType("audio/webm").content(new byte[0]))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(post("/api/v1/transcriptions").contentType("audio/flac").content(AUDIO))
        .andExpect(status().isBadRequest());

    assertThat(transcriptionCalls).hasValue(0);
  }

  /** Multipart would let the container buffer the upload in a temporary file; it is refused. */
  @Test
  void aMultipartUploadIsRefusedBeforeAnyPartIsRead() throws Exception {
    mockMvc(true)
        .perform(
            post("/api/v1/transcriptions")
                .contentType("multipart/form-data; boundary=x")
                .content("--x\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\n--x--"))
        .andExpect(status().isUnsupportedMediaType());

    assertThat(transcriptionCalls).hasValue(0);
  }

  private void assertLogsCarryNoContent() {
    for (ILoggingEvent event : logs.list) {
      String everything = event.getFormattedMessage() + throwableText(event.getThrowableProxy());
      assertThat(everything)
          .as("log line of %s", event.getLoggerName())
          .doesNotContain(RECOGNISED)
          .doesNotContain(AUDIO_MARKER);
    }
  }

  private java.util.List<String> formattedLines() {
    return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  private static String throwableText(IThrowableProxy proxy) {
    StringBuilder text = new StringBuilder();
    for (IThrowableProxy current = proxy; current != null; current = current.getCause()) {
      text.append('\n').append(current.getClassName()).append(": ").append(current.getMessage());
    }
    return text.toString();
  }
}
