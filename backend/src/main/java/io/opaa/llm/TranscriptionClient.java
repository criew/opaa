package io.opaa.llm;

import io.opaa.compliance.DataFlow;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

/**
 * Talks to an OpenAI-compatible transcription endpoint: {@code POST {baseUrl}/audio/transcriptions}
 * with the multipart fields {@code file}, {@code model}, {@code language} and {@code
 * response_format=json}, and {@code GET {baseUrl}/models} as the probe - free of charge on every
 * provider, unlike a probe with test audio.
 *
 * <p><b>Nothing about the person or the content leaves this class except the audio itself.</b> The
 * request carries no user field and no header naming an account or an address. No exception
 * message, and no cause attached to one, contains audio, recognised text or the endpoint's response
 * body; every message is technical English for the role's diagnostic.
 *
 * <p>The whole call - connect, headers and body - runs within {@link
 * TranscriptionProperties#timeout()}; the response is read bounded. Uses the JDK client like {@link
 * RerankClient}, so the installation-wide outbound proxy and the JVM truststore apply; redirects
 * are never followed, so the key cannot be replayed elsewhere.
 */
@DataFlow("transcription-model")
@Component
public class TranscriptionClient {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

  /** A transcript of a few minutes is a few kilobytes; anything near this is not a transcript. */
  static final int MAX_RESPONSE_BYTES = 1024 * 1024;

  private final ObjectMapper objectMapper;

  private final HttpClient httpClient =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(CONNECT_TIMEOUT)
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  /** Runs each call so it can be abandoned at its deadline, body read included. */
  private final ExecutorService callExecutor = Executors.newVirtualThreadPerTaskExecutor();

  public TranscriptionClient(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @PreDestroy
  void close() {
    httpClient.close();
    callExecutor.close();
  }

  /**
   * The endpoint could not be used for this call. The message never carries content. {@link
   * #answered()} tells a refusal of this one recording (the endpoint answered) from an endpoint
   * that could not be reached at all.
   */
  public static class TranscriptionUnavailableException extends RuntimeException {

    private final boolean timedOut;
    private final boolean answered;

    TranscriptionUnavailableException(String message, boolean timedOut) {
      this(message, timedOut, false);
    }

    TranscriptionUnavailableException(String message, boolean timedOut, boolean answered) {
      super(message);
      this.timedOut = timedOut;
      this.answered = answered;
    }

    public boolean timedOut() {
      return timedOut;
    }

    /** Whether the endpoint answered - with an error status or a body without a text. */
    public boolean answered() {
      return answered;
    }
  }

  /** A probe's failure; {@code null} from {@link #probe} means the endpoint answered. */
  public record ProbeFailure(String message, boolean timedOut) {}

  /**
   * Transcribes one recording.
   *
   * @return the recognised text, trimmed.
   * @throws TranscriptionUnavailableException when the endpoint is unreachable, refuses the call or
   *     answers something without a text.
   */
  public String transcribe(
      TranscriptionProperties properties, byte[] audio, TranscriptionAudioFormat format) {
    URI uri = endpointUri(properties.baseUrl(), "/audio/transcriptions");
    String boundary = "opaa-" + UUID.randomUUID();
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(multipartBody(boundary, properties, audio, format));
    authorize(request, properties);
    byte[] body = withinDeadline(properties.timeout(), () -> send(request.build()));
    return parseText(body);
  }

  /** Asks {@code GET {baseUrl}/models}; costs nothing and sends no audio. */
  public ProbeFailure probe(TranscriptionProperties properties) {
    try {
      URI uri = endpointUri(properties.baseUrl(), "/models");
      HttpRequest.Builder request = HttpRequest.newBuilder(uri).GET();
      authorize(request, properties);
      withinDeadline(properties.timeout(), () -> send(request.build()));
      return null;
    } catch (TranscriptionUnavailableException e) {
      return new ProbeFailure(e.getMessage(), e.timedOut());
    }
  }

  private static void authorize(HttpRequest.Builder request, TranscriptionProperties properties) {
    if (StringUtils.hasText(properties.apiKey())) {
      request.header("Authorization", "Bearer " + properties.apiKey());
    }
  }

  /** Sends and reads at most {@link #MAX_RESPONSE_BYTES}; any status but 200 is a failure. */
  private byte[] send(HttpRequest request) throws IOException, InterruptedException {
    HttpResponse<InputStream> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream body = response.body()) {
      if (response.statusCode() != 200) {
        throw new TranscriptionUnavailableException(
            describeStatus(response.statusCode()), false, true);
      }
      byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
      if (bytes.length > MAX_RESPONSE_BYTES) {
        throw new TranscriptionUnavailableException(
            "endpoint response exceeds the " + MAX_RESPONSE_BYTES + " byte limit", false, true);
      }
      return bytes;
    }
  }

  private <T> T withinDeadline(Duration timeout, Callable<T> call) {
    Future<T> future = callExecutor.submit(call);
    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new TranscriptionUnavailableException("request timed out", true);
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new TranscriptionUnavailableException("the call was interrupted", false);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof TranscriptionUnavailableException unavailable) {
        throw unavailable;
      }
      if (cause instanceof IOException io) {
        throw new TranscriptionUnavailableException(describeConnectionError(io), isTimeout(io));
      }
      throw new TranscriptionUnavailableException(
          "the call failed: " + cause.getClass().getSimpleName(), false);
    }
  }

  /** The recording is published from the caller's array itself; no copy of it is made. */
  private static HttpRequest.BodyPublisher multipartBody(
      String boundary,
      TranscriptionProperties properties,
      byte[] audio,
      TranscriptionAudioFormat format) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
    field(out, boundary, "model", properties.model());
    if (!properties.language().isEmpty()) {
      field(out, boundary, "language", properties.language());
    }
    field(out, boundary, "response_format", "json");
    ascii(
        out,
        "--"
            + boundary
            + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"recording."
            + format.extension()
            + "\"\r\nContent-Type: "
            + format.mediaType()
            + "\r\n\r\n");
    return HttpRequest.BodyPublishers.concat(
        HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()),
        HttpRequest.BodyPublishers.ofByteArray(audio),
        HttpRequest.BodyPublishers.ofString(
            "\r\n--" + boundary + "--\r\n", StandardCharsets.UTF_8));
  }

  private static void field(ByteArrayOutputStream out, String boundary, String name, String value) {
    ascii(
        out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
    out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
    ascii(out, "\r\n");
  }

  private static void ascii(ByteArrayOutputStream out, String text) {
    out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
  }

  /** Reads {@code text}; a parse failure is reported without its cause, which quotes the body. */
  private String parseText(byte[] body) {
    JsonNode root;
    try {
      root = objectMapper.readTree(body);
    } catch (JacksonException e) {
      throw new TranscriptionUnavailableException(
          "endpoint response is not valid JSON", false, true);
    }
    JsonNode text = root == null ? null : root.get("text");
    if (text == null || !text.isString()) {
      throw new TranscriptionUnavailableException(
          "endpoint response contains no text", false, true);
    }
    return text.asString().strip();
  }

  /** The cause is not attached: it may quote the configured address. */
  private static URI endpointUri(String baseUrl, String path) {
    return ModelRoleHttp.endpointUri(
        baseUrl, path, (message, cause) -> new TranscriptionUnavailableException(message, false));
  }

  private static final Map<Integer, String> ROLE_STATUS_TEXTS =
      Map.of(413, "HTTP 413: the endpoint refused the recording as too large");

  private static String describeStatus(int status) {
    return ModelRoleHttp.describeStatus(status, "transcription", ROLE_STATUS_TEXTS);
  }

  private static String describeConnectionError(IOException e) {
    return ModelRoleHttp.describeConnectionError(e, "connection attempt timed out");
  }

  private static boolean isTimeout(IOException e) {
    return ModelRoleHttp.isRequestTimeout(e);
  }
}
