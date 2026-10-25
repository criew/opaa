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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
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
  private static final String PERSON = "erika.musterfrau";

  private HttpServer upstream;
  private final AtomicInteger upstreamStatus = new AtomicInteger(200);
  private volatile String upstreamBody = "{\"text\":\"" + RECOGNISED + "\"}";
  private final AtomicInteger transcriptionCalls = new AtomicInteger();
  private volatile boolean upstreamStalls;
  private int maxConcurrent = 4;
  private TranscriptionModelRole role;
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
          if (upstreamStalls) {
            try {
              Thread.sleep(3_000);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
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
    return mockMvc(enabled, Duration.ofSeconds(5));
  }

  private MockMvc mockMvc(boolean enabled, Duration timeout) {
    return MockMvcBuilders.standaloneSetup(controller(enabled, timeout))
        .setControllerAdvice(
            new GlobalExceptionHandler(
                new UploadProperties(null, null, 52_428_800L, null, 0), Clock.systemUTC()))
        .addFilters(
            new TranscriptionMultipartGuard(new ObjectMapper(), java.time.Clock.systemUTC()),
            new RequestLoggingFilter())
        .build();
  }

  private TranscriptionController controller(boolean enabled, Duration timeout) {
    role = role(enabled, timeout);
    return new TranscriptionController(role);
  }

  private TranscriptionModelRole role(boolean enabled, Duration timeout) {
    TranscriptionProperties properties =
        new TranscriptionProperties(
            enabled,
            "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1",
            "whisper",
            "",
            "de",
            MAX_AUDIO_BYTES,
            timeout,
            maxConcurrent);
    TranscriptionModelRole role =
        new TranscriptionModelRole(properties, client, new FeatureFlags(new MockEnvironment()));
    role.refresh();
    return role;
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
  void aTimedOutUpstreamIs502AndNothingOfTheRecordingIsLogged() throws Exception {
    MockMvc mockMvc = mockMvc(true, Duration.ofMillis(500));
    upstreamStalls = true;

    mockMvc
        .perform(post("/api/v1/transcriptions").contentType("audio/webm").content(AUDIO))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error").value(TranscriptionController.UPSTREAM_FAILED_MESSAGE));

    assertLogsCarryNoContent();
    assertThat(formattedLines()).anyMatch(line -> line.contains("request timed out"));
  }

  /** The request log names method, path, status and duration - nobody, and nothing of the body. */
  @Test
  void theRequestLogCarriesNoPersonForADictation() throws Exception {
    mockMvc(true)
        .perform(
            post("/api/v1/transcriptions")
                .contentType("audio/webm")
                .content(AUDIO)
                .principal(() -> PERSON)
                .with(
                    request -> {
                      request.setRemoteAddr("10.20.30.40");
                      return request;
                    }))
        .andExpect(status().isOk());

    assertLogsCarryNoContent();
    assertThat(formattedLines())
        .filteredOn(line -> line.contains("/api/v1/transcriptions"))
        .singleElement()
        .satisfies(line -> assertThat(line).matches("POST /api/v1/transcriptions 200 \\d+ms"));
    for (ILoggingEvent event : logs.list) {
      String everything =
          event.getFormattedMessage() + event.getMDCPropertyMap() + event.getKeyValuePairs();
      assertThat(everything).doesNotContain(PERSON).doesNotContain("10.20.30.40");
    }
  }

  /**
   * The servlet container writes multipart parts above its file-size-threshold to a temporary file;
   * a dictation is no multipart request, and the controller reads nothing but the raw body.
   */
  @Test
  void aDictationIsReadFromTheRawBodyAndNeverAsMultipartParts() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/transcriptions");
    request.setContentType("audio/webm;codecs=opus");
    request.setContent(AUDIO);
    assertThat(new StandardServletMultipartResolver().isMultipart(request)).isFalse();

    HttpServletRequest rawBodyOnly = untouchable(request);

    assertThat(controller(true, Duration.ofSeconds(5)).createTranscription(rawBodyOnly).getText())
        .isEqualTo(RECOGNISED);
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
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.error").value(TranscriptionMultipartGuard.MESSAGE));

    assertThat(transcriptionCalls).hasValue(0);
  }

  /** The guard answers without touching parts or parameters, so nothing is parsed to disk. */
  @Test
  void theGuardRefusesMultipartWithoutReadingTheRequest() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/transcriptions");
    request.setContentType("multipart/form-data; boundary=x");
    request.setContent(AUDIO);
    org.springframework.mock.web.MockHttpServletResponse response =
        new org.springframework.mock.web.MockHttpServletResponse();
    jakarta.servlet.FilterChain chain =
        (req, res) -> {
          throw new AssertionError("the chain was continued");
        };

    new TranscriptionMultipartGuard(new ObjectMapper(), java.time.Clock.systemUTC())
        .doFilter(untouchable(request), response, chain);

    assertThat(response.getStatus()).isEqualTo(415);
  }

  @Test
  void theGuardIsFirstInTheFilterChain() {
    assertThat(
            new TranscriptionWebConfiguration()
                .transcriptionMultipartGuard(new ObjectMapper(), java.time.Clock.systemUTC())
                .getOrder())
        .isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
  }

  /** A refusal of one recording is answered with 502 and leaves dictation on for everybody. */
  @Test
  void aRefusedRecordingIs502AndDictationStaysAvailable() throws Exception {
    MockMvc mockMvc = mockMvc(true);
    upstreamStatus.set(400);
    upstreamBody = "{\"error\":\"unsupported format\"}";

    mockMvc
        .perform(post("/api/v1/transcriptions").contentType("audio/mp4").content(AUDIO))
        .andExpect(status().isBadGateway());

    mockMvc
        .perform(get("/api/v1/transcriptions/availability"))
        .andExpect(jsonPath("$.available").value(true));
    assertLogsCarryNoContent();
    assertThat(logs.list).allMatch(event -> event.getThrowableProxy() == null);
  }

  @Test
  void allSlotsTakenIs503AndNothingIsRead() throws Exception {
    maxConcurrent = 1;
    MockMvc mockMvc = mockMvc(true);
    try (TranscriptionModelRole.Slot taken = role.reserve()) {
      mockMvc
          .perform(post("/api/v1/transcriptions").contentType("audio/webm").content(AUDIO))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.error").value(TranscriptionController.BUSY_MESSAGE));
    }
    assertThat(transcriptionCalls).hasValue(0);

    mockMvc
        .perform(post("/api/v1/transcriptions").contentType("audio/webm").content(AUDIO))
        .andExpect(status().isOk());
  }

  /** A request whose parts and parameters must not be asked for: asking fails the test. */
  private static HttpServletRequest untouchable(HttpServletRequest request) {
    return new HttpServletRequestWrapper(request) {
      @Override
      public Collection<Part> getParts() {
        throw new AssertionError("multipart parts were read");
      }

      @Override
      public Part getPart(String name) {
        throw new AssertionError("multipart part was read");
      }

      @Override
      public String getParameter(String name) {
        throw new AssertionError("request parameters were parsed");
      }

      @Override
      public Map<String, String[]> getParameterMap() {
        throw new AssertionError("request parameters were parsed");
      }
    };
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
