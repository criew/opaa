package io.opaa.llm.textrecognition;

import io.opaa.api.types.TextRecognitionConnectionType;
import io.opaa.compliance.DataFlow;
import io.opaa.llm.ModelRoleHttp;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Talks to the service of the text recognition role, in exactly two ways:
 *
 * <ul>
 *   <li>{@link TextRecognitionConnectionType#DOCLING_SERVE}: {@code POST {baseUrl}/v1/convert/file}
 *       with the whole PDF and the procedure options of every call ({@link #DOCLING_OPTIONS}: OCR
 *       forced, RapidOCR, German, tables, DoclingDocument JSON), key as {@code X-Api-Key}; {@code
 *       GET {baseUrl}/health} and, with the key, {@code GET {baseUrl}/v1/status/poll/{id}} of a
 *       task that does not exist as the probe: 404 there means the key was accepted.
 *   <li>{@link TextRecognitionConnectionType#OPENAI_VISION}: {@code POST
 *       {baseUrl}/chat/completions} with one page image and {@link TextRecognitionVisionPrompt},
 *       key as bearer token; {@code GET {baseUrl}/models} as the probe.
 * </ul>
 *
 * <p><b>Nothing leaves this class but the document or page and the fixed options.</b> No request
 * names an account, a library or a file name beyond a fixed one; no exception message contains
 * document content, recognised text or the service's response body.
 *
 * <p>A failure is either {@link TextRecognitionUnavailableException} (the service: unreachable,
 * timeout, key refused, overload, server error) or {@link TextRecognitionRejectedException} (the
 * service answered and refused this one document). Uses the JDK client like {@code
 * TranscriptionClient}, so the outbound proxy and the JVM truststore apply; redirects are never
 * followed, so the key cannot be replayed elsewhere. The answer is translated into chunks by the
 * indexing, not here.
 */
@DataFlow("text-recognition-model")
@Component
public class TextRecognitionClient {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

  /** A DoclingDocument of a long scan is a few megabytes; anything beyond this is no answer. */
  static final int MAX_RESPONSE_BYTES = 64 * 1024 * 1024;

  /** The test page OPAA ships, as scan without a text layer and as image. */
  static final String TEST_PAGE_PDF = "/text-recognition/test-page.pdf";

  static final String TEST_PAGE_PNG = "/text-recognition/test-page.png";

  /** A task id docling-serve never issues; polling it answers 404 once the key is accepted. */
  static final String KEY_CHECK_PATH = "/v1/status/poll/opaa-key-check";

  /**
   * The procedure options sent with every conversion, the test page included: a foreign
   * docling-serve then behaves like the shipped container, provided it has the models.
   */
  static final Map<String, String> DOCLING_OPTIONS = doclingOptions();

  private final ObjectMapper objectMapper;

  private final HttpClient httpClient =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(CONNECT_TIMEOUT)
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  /** Runs each call so it can be abandoned at its deadline, body read included. */
  private final ExecutorService callExecutor = Executors.newVirtualThreadPerTaskExecutor();

  public TextRecognitionClient(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @PreDestroy
  void close() {
    httpClient.close();
    callExecutor.close();
  }

  /**
   * The service could not take the call: not reachable, timed out, refused the key, overloaded or
   * failed itself. Says nothing about the document; a later attempt may succeed.
   */
  public static class TextRecognitionUnavailableException extends RuntimeException {

    private final boolean timedOut;
    private final int status;

    TextRecognitionUnavailableException(String message, boolean timedOut, int status) {
      super(message);
      this.timedOut = timedOut;
      this.status = status;
    }

    public boolean timedOut() {
      return timedOut;
    }

    /** The HTTP status the service answered with, 0 when it did not answer. */
    public int status() {
      return status;
    }

    /** Whether the service answered at all - with an error status or an unusable body. */
    public boolean answered() {
      return status != 0;
    }

    /** Whether the service refused the key (HTTP 401 or 403). */
    public boolean keyRejected() {
      return status == 401 || status == 403;
    }

    /**
     * Whether the role cannot be used as configured: no answer, key refused, wrong address (404,
     * redirect) or a server error. Overload (408, 429) and an unusable body of one answer are not.
     */
    public boolean roleUnusable() {
      return !answered()
          || keyRejected()
          || status == 404
          || (status >= 300 && status < 400)
          || status >= 500;
    }
  }

  /** The service answered and refused this one document, e.g. a broken PDF. */
  public static class TextRecognitionRejectedException extends RuntimeException {

    private final int status;

    TextRecognitionRejectedException(String message, int status) {
      super(message);
      this.status = status;
    }

    /** The HTTP status of the refusal, 200 when the service reported a failed conversion. */
    public int status() {
      return status;
    }
  }

  /**
   * The DoclingDocument docling-serve made of a PDF.
   *
   * @param document the {@code json_content} of the answer: pages, texts, tables with page, box and
   *     character range per element
   * @param partial whether docling-serve reported {@code partial_success}
   */
  public record DoclingConversion(JsonNode document, boolean partial) {}

  /**
   * A probe's failure; {@code null} from {@link #probe} means the service answered and accepted the
   * key.
   */
  public record ProbeFailure(String message, boolean timedOut, boolean keyRejected) {}

  /** What the connection test found. */
  public enum TestOutcome {
    /** Reachable, and it read text from the test page. */
    READY,
    /** The service refused the key (HTTP 401 or 403). */
    KEY_REJECTED,
    /** Reachable, but the OCR engine, its models or the vision model are missing. */
    ENGINE_MISSING,
    /** The service answered without text. */
    NO_TEXT,
    /** Not reachable, timed out or answered unexpectedly. */
    UNREACHABLE
  }

  /** The step of the connection test that failed. */
  public enum FailedStage {
    /** The address: no connection, timeout, redirect or an unexpected answer of the probe. */
    ADDRESS,
    /** The key: HTTP 401 or 403, at the probe or at the test page. */
    AUTHENTICATION,
    /** Reading the test page, timeout and unreadable answer included, or the missing model. */
    RECOGNITION
  }

  /**
   * A test result; {@code diagnostic} is technical English without content, {@code null} if OK;
   * {@code failedStage} is {@code null} exactly for {@link TestOutcome#READY}.
   */
  public record TestResult(
      TestOutcome outcome, String diagnostic, boolean timedOut, FailedStage failedStage) {}

  /**
   * Converts a whole PDF with docling-serve.
   *
   * @throws TextRecognitionUnavailableException when the service could not take the call
   * @throws TextRecognitionRejectedException when it refused this document
   */
  public DoclingConversion convertWithDocling(
      TextRecognitionEndpoint endpoint, Duration timeout, byte[] pdf) {
    Answer answer = withinDeadline(timeout, () -> exchange(doclingConversion(endpoint, pdf)));
    requireOk(answer);
    JsonNode root = parse(answer.body());
    String status = root.path("status").asString("");
    if ("failure".equals(status) || "skipped".equals(status)) {
      throw new TextRecognitionRejectedException(
          "docling-serve reported the conversion as " + status, 200);
    }
    JsonNode document = root.path("document").get("json_content");
    if (document == null || !document.isObject()) {
      throw new TextRecognitionUnavailableException(
          "docling-serve answer contains no DoclingDocument", false, 200);
    }
    return new DoclingConversion(document, "partial_success".equals(status));
  }

  /**
   * Reads one page image with the vision model.
   *
   * @return the page as Markdown, trimmed; empty for a page without text
   * @throws TextRecognitionUnavailableException when the service could not take the call
   * @throws TextRecognitionRejectedException when it refused this page
   */
  public String recognizePageWithVision(
      TextRecognitionEndpoint endpoint, Duration timeout, byte[] pageImage, String mediaType) {
    Answer answer =
        withinDeadline(
            timeout, () -> exchange(visionRequest(endpoint, pageImage, mediaType).build()));
    requireOk(answer);
    return visionText(parse(answer.body()));
  }

  /** Asks the free probe of the connection type, key included; sends no document. */
  public ProbeFailure probe(TextRecognitionEndpoint endpoint, Duration timeout) {
    try {
      Answer answer = probeExchange(endpoint, System.nanoTime() + timeout.toNanos());
      if (answer.status() != 200) {
        boolean keyRejected = answer.status() == 401 || answer.status() == 403;
        return new ProbeFailure(describeStatus(answer.status()), false, keyRejected);
      }
      return null;
    } catch (TextRecognitionUnavailableException e) {
      return new ProbeFailure(e.getMessage(), e.timedOut(), false);
    }
  }

  /**
   * The probe's decisive answer. docling-serve answers {@code /health} without a key, so the key is
   * checked on a protected route that converts nothing: polling a task that does not exist answers
   * 404 with an accepted key and 401 without.
   */
  private Answer probeExchange(TextRecognitionEndpoint endpoint, long deadlineNanos) {
    Answer reachable =
        withinDeadline(remaining(deadlineNanos), () -> exchange(probeRequest(endpoint).build()));
    if (reachable.status() != 200
        || endpoint.connectionType() != TextRecognitionConnectionType.DOCLING_SERVE) {
      return reachable;
    }
    Answer key =
        withinDeadline(remaining(deadlineNanos), () -> exchange(keyCheckRequest(endpoint)));
    return key.status() == 404 || key.status() == 200 ? reachable : key;
  }

  /**
   * The connection test: the probe, then the shipped test page with exactly the options of the
   * operation, all within one {@code timeout}. Sends nothing from a library.
   */
  public TestResult test(TextRecognitionEndpoint endpoint, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    Answer probe;
    try {
      probe = probeExchange(endpoint, deadline);
    } catch (TextRecognitionUnavailableException e) {
      return new TestResult(
          TestOutcome.UNREACHABLE, e.getMessage(), e.timedOut(), FailedStage.ADDRESS);
    }
    if (probe.status() == 401 || probe.status() == 403) {
      return new TestResult(
          TestOutcome.KEY_REJECTED,
          describeStatus(probe.status()),
          false,
          FailedStage.AUTHENTICATION);
    }
    if (probe.status() != 200) {
      return new TestResult(
          TestOutcome.UNREACHABLE, describeStatus(probe.status()), false, FailedStage.ADDRESS);
    }
    if (endpoint.connectionType() == TextRecognitionConnectionType.OPENAI_VISION
        && !offersModel(probe.body(), endpoint.model())) {
      return new TestResult(
          TestOutcome.ENGINE_MISSING,
          "the endpoint does not list the model",
          false,
          FailedStage.RECOGNITION);
    }
    try {
      Answer page = withinDeadline(remaining(deadline), () -> exchange(testPageRequest(endpoint)));
      return testResult(endpoint, page);
    } catch (TextRecognitionUnavailableException e) {
      return new TestResult(
          TestOutcome.UNREACHABLE, e.getMessage(), e.timedOut(), FailedStage.RECOGNITION);
    }
  }

  private TestResult testResult(TextRecognitionEndpoint endpoint, Answer page) {
    int status = page.status();
    if (status == 401 || status == 403) {
      return new TestResult(
          TestOutcome.KEY_REJECTED, describeStatus(status), false, FailedStage.AUTHENTICATION);
    }
    if (status == 404 || (status >= 300 && status < 400) || status == 408 || status == 429) {
      return new TestResult(
          TestOutcome.UNREACHABLE, describeStatus(status), false, FailedStage.RECOGNITION);
    }
    if (status != 200) {
      return new TestResult(
          TestOutcome.ENGINE_MISSING, describeStatus(status), false, FailedStage.RECOGNITION);
    }
    JsonNode root;
    try {
      root = parse(page.body());
    } catch (TextRecognitionUnavailableException e) {
      return new TestResult(
          TestOutcome.UNREACHABLE, e.getMessage(), false, FailedStage.RECOGNITION);
    }
    String text;
    if (endpoint.connectionType() == TextRecognitionConnectionType.DOCLING_SERVE) {
      String conversion = root.path("status").asString("");
      if (!conversion.equals("success") && !conversion.equals("partial_success")) {
        return new TestResult(
            TestOutcome.ENGINE_MISSING,
            "docling-serve could not convert the test page (" + conversion + ")",
            false,
            FailedStage.RECOGNITION);
      }
      text = doclingText(root.path("document").path("json_content"));
    } else {
      text = visionText(root);
    }
    return text.isBlank()
        ? new TestResult(
            TestOutcome.NO_TEXT, "the service returned no text", false, FailedStage.RECOGNITION)
        : new TestResult(TestOutcome.READY, null, false, null);
  }

  /** The text items and table cells of a DoclingDocument, joined; empty when there are none. */
  static String doclingText(JsonNode document) {
    StringBuilder text = new StringBuilder();
    for (JsonNode item : document.path("texts")) {
      text.append(item.path("text").asString("")).append('\n');
    }
    for (JsonNode table : document.path("tables")) {
      for (JsonNode cell : table.path("data").path("table_cells")) {
        text.append(cell.path("text").asString("")).append('\n');
      }
    }
    return text.toString().strip();
  }

  private static String visionText(JsonNode root) {
    JsonNode content = root.path("choices").path(0).path("message").path("content");
    if (!content.isString()) {
      throw new TextRecognitionUnavailableException(
          "endpoint response contains no message content", false, 200);
    }
    return content.asString().strip();
  }

  private boolean offersModel(byte[] models, String model) {
    JsonNode data;
    try {
      data = objectMapper.readTree(models).path("data");
    } catch (JacksonException e) {
      return true;
    }
    if (!data.isArray() || data.isEmpty()) {
      return true;
    }
    for (JsonNode entry : data) {
      if (model.equals(entry.path("id").asString(""))) {
        return true;
      }
    }
    return false;
  }

  private HttpRequest.Builder probeRequest(TextRecognitionEndpoint endpoint) {
    String path =
        endpoint.connectionType() == TextRecognitionConnectionType.DOCLING_SERVE
            ? "/health"
            : "/models";
    return authorized(
        HttpRequest.newBuilder(endpointUri(endpoint.baseUrl(), path)).GET(), endpoint);
  }

  private HttpRequest keyCheckRequest(TextRecognitionEndpoint endpoint) {
    return authorized(
            HttpRequest.newBuilder(endpointUri(endpoint.baseUrl(), KEY_CHECK_PATH)).GET(), endpoint)
        .build();
  }

  private HttpRequest testPageRequest(TextRecognitionEndpoint endpoint) {
    if (endpoint.connectionType() == TextRecognitionConnectionType.DOCLING_SERVE) {
      return doclingConversion(endpoint, resource(TEST_PAGE_PDF));
    }
    return visionRequest(endpoint, resource(TEST_PAGE_PNG), "image/png").build();
  }

  private HttpRequest doclingConversion(TextRecognitionEndpoint endpoint, byte[] pdf) {
    String boundary = "opaa-" + UUID.randomUUID();
    ByteArrayOutputStream head = new ByteArrayOutputStream(1024);
    DOCLING_OPTIONS.forEach((name, value) -> field(head, boundary, name, value));
    ascii(
        head,
        "--"
            + boundary
            + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"document.pdf\""
            + "\r\nContent-Type: application/pdf\r\n\r\n");
    HttpRequest.BodyPublisher body =
        HttpRequest.BodyPublishers.concat(
            HttpRequest.BodyPublishers.ofByteArray(head.toByteArray()),
            HttpRequest.BodyPublishers.ofByteArray(pdf),
            HttpRequest.BodyPublishers.ofString(
                "\r\n--" + boundary + "--\r\n", StandardCharsets.UTF_8));
    return authorized(
            HttpRequest.newBuilder(endpointUri(endpoint.baseUrl(), "/v1/convert/file"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .POST(body),
            endpoint)
        .build();
  }

  private HttpRequest.Builder visionRequest(
      TextRecognitionEndpoint endpoint, byte[] pageImage, String mediaType) {
    ObjectNode request = objectMapper.createObjectNode();
    request.put("model", endpoint.model());
    request.put("temperature", 0);
    ArrayNode content =
        request.putArray("messages").addObject().put("role", "user").putArray("content");
    content.addObject().put("type", "text").put("text", TextRecognitionVisionPrompt.TEXT);
    content
        .addObject()
        .put("type", "image_url")
        .putObject("image_url")
        .put(
            "url",
            "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(pageImage));
    return authorized(
        HttpRequest.newBuilder(endpointUri(endpoint.baseUrl(), "/chat/completions"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(request))),
        endpoint);
  }

  /** The key goes only to the endpoint it was stored for, in the header of its connection type. */
  private static HttpRequest.Builder authorized(
      HttpRequest.Builder request, TextRecognitionEndpoint endpoint) {
    if (StringUtils.hasText(endpoint.apiKey())) {
      if (endpoint.connectionType() == TextRecognitionConnectionType.DOCLING_SERVE) {
        request.header("X-Api-Key", endpoint.apiKey());
      } else {
        request.header("Authorization", "Bearer " + endpoint.apiKey());
      }
    }
    return request;
  }

  /** A status other than 200 as the failure class of the operation. */
  private static void requireOk(Answer answer) {
    int status = answer.status();
    if (status == 200) {
      return;
    }
    boolean service =
        status < 400
            || status == 401
            || status == 403
            || status == 404
            || status == 408
            || status == 429
            || status >= 500;
    if (service) {
      throw new TextRecognitionUnavailableException(describeStatus(status), false, status);
    }
    throw new TextRecognitionRejectedException(describeStatus(status), status);
  }

  /** The status and at most {@link #MAX_RESPONSE_BYTES} of the body; never throws on a status. */
  private Answer exchange(HttpRequest request) throws IOException, InterruptedException {
    HttpResponse<InputStream> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream body = response.body()) {
      if (response.statusCode() != 200) {
        return new Answer(response.statusCode(), new byte[0]);
      }
      byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
      if (bytes.length > MAX_RESPONSE_BYTES) {
        throw new TextRecognitionUnavailableException(
            "endpoint response exceeds the " + MAX_RESPONSE_BYTES + " byte limit", false, 200);
      }
      return new Answer(200, bytes);
    }
  }

  private record Answer(int status, byte[] body) {}

  /** A parse failure is reported without its cause, which quotes the body. */
  private JsonNode parse(byte[] body) {
    try {
      JsonNode root = objectMapper.readTree(body);
      if (root == null || !root.isObject()) {
        throw new TextRecognitionUnavailableException(
            "endpoint response is not a JSON object", false, 200);
      }
      return root;
    } catch (JacksonException e) {
      throw new TextRecognitionUnavailableException(
          "endpoint response is not valid JSON", false, 200);
    }
  }

  /** What is left of a deadline shared by several requests; zero once it has passed. */
  private static Duration remaining(long deadlineNanos) {
    return Duration.ofNanos(Math.max(0, deadlineNanos - System.nanoTime()));
  }

  private <T> T withinDeadline(Duration timeout, Callable<T> call) {
    Future<T> future = callExecutor.submit(call);
    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new TextRecognitionUnavailableException("request timed out", true, 0);
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new TextRecognitionUnavailableException("the call was interrupted", false, 0);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof TextRecognitionUnavailableException unavailable) {
        throw unavailable;
      }
      if (cause instanceof IOException io) {
        throw new TextRecognitionUnavailableException(
            ModelRoleHttp.describeConnectionError(io, "connection attempt timed out"),
            ModelRoleHttp.isRequestTimeout(io),
            0);
      }
      throw new TextRecognitionUnavailableException(
          "the call failed: " + cause.getClass().getSimpleName(), false, 0);
    }
  }

  private static void field(ByteArrayOutputStream out, String boundary, String name, String value) {
    ascii(
        out,
        "--"
            + boundary
            + "\r\nContent-Disposition: form-data; name=\""
            + name
            + "\"\r\n\r\n"
            + value
            + "\r\n");
  }

  private static void ascii(ByteArrayOutputStream out, String text) {
    out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
  }

  /** The cause is not attached: it may quote the configured address. */
  private static URI endpointUri(String baseUrl, String path) {
    return ModelRoleHttp.endpointUri(
        baseUrl,
        path,
        (message, cause) -> new TextRecognitionUnavailableException(message, false, 0));
  }

  private static String describeStatus(int status) {
    if (status >= 300 && status < 400) {
      return "HTTP " + status + ": the endpoint answered with a redirect, which is not followed";
    }
    return ModelRoleHttp.describeStatus(
        status,
        "text recognition",
        Map.of(
            413, "HTTP 413: the endpoint refused the document as too large",
            429, "HTTP 429: the endpoint is overloaded"));
  }

  private static byte[] resource(String path) {
    try (InputStream in = TextRecognitionClient.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("test page " + path + " is missing from the build");
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Map<String, String> doclingOptions() {
    Map<String, String> options = new LinkedHashMap<>();
    options.put("from_formats", "pdf");
    options.put("to_formats", "json");
    options.put("do_ocr", "true");
    options.put("force_ocr", "true");
    options.put("ocr_engine", "rapidocr");
    options.put("ocr_lang", "de");
    options.put("do_table_structure", "true");
    options.put("table_mode", "accurate");
    options.put("image_export_mode", "placeholder");
    options.put("abort_on_error", "false");
    return Collections.unmodifiableMap(options);
  }
}
