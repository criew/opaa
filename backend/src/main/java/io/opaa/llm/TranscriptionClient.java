package io.opaa.llm;

import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Talks to an OpenAI-compatible transcription endpoint (#1926): {@code POST
 * {baseUrl}/audio/transcriptions} with the multipart fields {@code file}, {@code model}, {@code
 * language} and {@code response_format=json}, and {@code GET {baseUrl}/models} as the probe - free
 * of charge on every provider, unlike a probe with test audio.
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

  /** The endpoint could not be used for this call. The message never carries content. */
  public static class TranscriptionUnavailableException extends RuntimeException {

    private final boolean timedOut;

    TranscriptionUnavailableException(String message, boolean timedOut) {
      super(message);
      this.timedOut = timedOut;
    }

    public boolean timedOut() {
      return timedOut;
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
            .POST(
                HttpRequest.BodyPublishers.ofByteArray(
                    multipartBody(boundary, properties, audio, format)));
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
        throw new TranscriptionUnavailableException(describeStatus(response.statusCode()), false);
      }
      byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
      if (bytes.length > MAX_RESPONSE_BYTES) {
        throw new TranscriptionUnavailableException(
            "endpoint response exceeds the " + MAX_RESPONSE_BYTES + " byte limit", false);
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

  private byte[] multipartBody(
      String boundary,
      TranscriptionProperties properties,
      byte[] audio,
      TranscriptionAudioFormat format) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(audio.length + 1024);
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
    out.writeBytes(audio);
    ascii(out, "\r\n--" + boundary + "--\r\n");
    return out.toByteArray();
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
      throw new TranscriptionUnavailableException("endpoint response is not valid JSON", false);
    }
    JsonNode text = root == null ? null : root.get("text");
    if (text == null || !text.isString()) {
      throw new TranscriptionUnavailableException("endpoint response contains no text", false);
    }
    return text.asString().strip();
  }

  private static URI endpointUri(String baseUrl, String path) {
    URI uri;
    try {
      uri = ModelEndpointUri.append(baseUrl, path);
    } catch (IllegalArgumentException e) {
      throw new TranscriptionUnavailableException("base URL carries credentials", false);
    } catch (URISyntaxException e) {
      throw new TranscriptionUnavailableException("base URL is not a valid URI", false);
    }
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      throw new TranscriptionUnavailableException(
          "base URL must start with http:// or https://", false);
    }
    return uri;
  }

  private static String describeStatus(int status) {
    return switch (status) {
      case 401 -> "HTTP 401: authentication failed, check the access key";
      case 403 -> "HTTP 403: the endpoint refused access";
      case 404 -> "HTTP 404: unknown path or model identifier";
      case 413 -> "HTTP 413: the endpoint refused the recording as too large";
      default -> "HTTP " + status + " from the transcription endpoint";
    };
  }

  private static String describeConnectionError(IOException e) {
    if (e instanceof UnknownHostException) {
      return "host not found (DNS resolution failed)";
    }
    if (e instanceof ConnectException) {
      return "connection refused";
    }
    if (e instanceof HttpConnectTimeoutException) {
      return "connection attempt timed out";
    }
    if (isTimeout(e)) {
      return "request timed out";
    }
    if (e instanceof SSLException) {
      return "TLS handshake failed";
    }
    if (e instanceof InterruptedIOException) {
      return "request interrupted";
    }
    return "endpoint not reachable";
  }

  private static boolean isTimeout(IOException e) {
    return (e instanceof HttpTimeoutException && !(e instanceof HttpConnectTimeoutException))
        || e instanceof SocketTimeoutException;
  }
}
