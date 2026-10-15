package io.opaa.indexing.source.dropbox;

import io.opaa.compliance.DataFlow;
import io.opaa.indexing.spi.RequestBudget;
import io.opaa.outbound.ProxyRefusal;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.BoundedStreams;
import io.opaa.sourceaccess.RateLimitHandling;
import io.opaa.sourceaccess.RateLimitPolicy;
import io.opaa.sourceaccess.RedirectFollowingFetcher;
import io.opaa.sourceaccess.Sleeper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Dropbox API v2 as the connector uses it: RPC calls ({@code POST} with a JSON body) to the API
 * host and content calls ({@code POST} with the argument in {@code Dropbox-API-Arg}) to the content
 * host, both over {@link RedirectFollowingFetcher#sendWithBody} - target validation first, no
 * redirect followed. The access token is asked anew per request, every attempt is charged to the
 * run's {@link RequestBudget}, and a {@code 429} is waited out as its {@code Retry-After} says. A
 * rejected token ({@code 401}) is retried once when the run's credentials hold a renewed one; every
 * other failure becomes a {@link DropboxApiException}.
 */
@DataFlow("source-dropbox")
final class DropboxApi {

  private static final Logger log = LoggerFactory.getLogger(DropboxApi.class);

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final long MAX_JSON_BYTES = 16L * 1024 * 1024;
  private static final long MAX_ERROR_BYTES = 64L * 1024;

  private final URI apiBase;
  private final URI contentBase;
  private final Supplier<String> token;
  private final Predicate<String> renewedAfterRejection;
  private final HttpClient httpClient;
  private final TargetAddressValidator targetAddressValidator;
  private final Duration timeout;
  private final Duration downloadTimeout;
  private final RequestBudget budget;
  private final RateLimitHandling rateLimit;

  /** The {@code Dropbox-API-Path-Root} of every call, {@code null} for the member's own folder. */
  private String pathRoot;

  /** Where downloads are staged; {@code null} for the system temp directory. */
  private Path tempDirectory;

  DropboxApi(
      URI apiBase,
      URI contentBase,
      Supplier<String> token,
      Predicate<String> renewedAfterRejection,
      HttpClient httpClient,
      TargetAddressValidator targetAddressValidator,
      Duration timeout,
      Duration downloadTimeout,
      RequestBudget budget,
      int maxRetries,
      Duration maxRetryWait,
      Sleeper sleeper) {
    this.apiBase = apiBase;
    this.contentBase = contentBase;
    this.token = token;
    this.renewedAfterRejection = renewedAfterRejection;
    this.httpClient = httpClient;
    this.targetAddressValidator = targetAddressValidator;
    this.timeout = timeout;
    this.downloadTimeout = downloadTimeout;
    this.budget = budget;
    this.rateLimit =
        new RateLimitHandling(
            maxRetries <= 0 ? RateLimitPolicy.NONE : RateLimitPolicy.of(maxRetries, maxRetryWait),
            sleeper,
            budget);
  }

  DropboxApi stagingIn(Path directory) {
    this.tempDirectory = directory;
    return this;
  }

  RequestBudget budget() {
    return budget;
  }

  /**
   * Every later call reads from the namespace {@code namespaceId} as root - the team space of a
   * member in a team with one.
   */
  void rootAt(String namespaceId) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put(".tag", "root");
    root.put("root", namespaceId);
    this.pathRoot = asciiJson(root);
  }

  /**
   * {@code POST <api>/2/<route>} with {@code args} as JSON body - {@code null} for a route without
   * arguments - answered with a JSON object.
   */
  JsonNode rpc(String route, Map<String, Object> args)
      throws DropboxApiException, InterruptedException {
    byte[] body =
        args == null ? "null".getBytes(StandardCharsets.UTF_8) : JSON.writeValueAsBytes(args);
    try (Answer answer =
        send(
            apiBase + "/2/" + route,
            HttpRequest.BodyPublishers.ofByteArray(body),
            "application/json",
            Map.of())) {
      byte[] bytes = BoundedStreams.readFully(answer.response().body(), MAX_JSON_BYTES);
      return bytes.length == 0 ? JSON.createObjectNode() : JSON.readTree(bytes);
    } catch (JacksonException | IOException e) {
      throw new DropboxApiException(
          DropboxApiException.Kind.TRANSIENT, 200, null, "Dropbox hat unlesbar geantwortet.");
    }
  }

  /**
   * The content of {@code POST <content>/2/<route>} - a download or an export - in a temp file
   * <b>the caller deletes</b>, at most {@code maxBytes} and within the download timeout counted
   * from the answer's start; an overrun leaves no temp file.
   */
  Path download(String route, Map<String, Object> args, long maxBytes)
      throws DropboxApiException, InterruptedException {
    Path temp = null;
    try (Answer answer =
        send(
            contentBase + "/2/" + route,
            HttpRequest.BodyPublishers.noBody(),
            "application/octet-stream",
            Map.of("Dropbox-API-Arg", asciiJson(args)))) {
      long deadline = System.nanoTime() + downloadTimeout.toNanos();
      temp =
          tempDirectory == null
              ? Files.createTempFile("opaa-dropbox-", ".bin")
              : Files.createTempFile(tempDirectory, "opaa-dropbox-", ".bin");
      try (InputStream in = answer.response().body();
          OutputStream out = Files.newOutputStream(temp)) {
        BoundedStreams.copyBefore(in, out, maxBytes, deadline);
      }
      budget.meter().recordBytes(Files.size(temp));
      return temp;
    } catch (BoundedStreams.LimitExceededException e) {
      deleteQuietly(temp);
      throw new DropboxApiException(
          DropboxApiException.Kind.TOO_LARGE,
          200,
          null,
          "Die Datei ist größer als " + maxBytes + " Bytes.");
    } catch (HttpTimeoutException e) {
      deleteQuietly(temp);
      throw new DropboxApiException(
          DropboxApiException.Kind.TRANSIENT,
          200,
          null,
          "Die Übertragung von Dropbox dauerte länger als " + downloadTimeout.toSeconds() + " s.");
    } catch (IOException e) {
      deleteQuietly(temp);
      throw new DropboxApiException(
          DropboxApiException.Kind.TRANSIENT, 200, null, "Die Übertragung von Dropbox brach ab.");
    }
  }

  private record Answer(HttpResponse<InputStream> response) implements AutoCloseable {
    @Override
    public void close() {
      try {
        response.body().close();
      } catch (IOException e) {
        // nothing left to read
      }
    }
  }

  /** One successful answer; throttles are waited out, every other status becomes a failure. */
  private Answer send(
      String url, HttpRequest.BodyPublisher body, String contentType, Map<String, String> extra)
      throws DropboxApiException, InterruptedException {
    boolean renewed = false;
    while (true) {
      String sent = token.get();
      Map<String, String> headers = new LinkedHashMap<>(extra);
      headers.put("Authorization", "Bearer " + sent);
      if (pathRoot != null) {
        headers.put("Dropbox-API-Path-Root", pathRoot);
      }
      HttpResponse<InputStream> response;
      try {
        response =
            RedirectFollowingFetcher.sendWithBody(
                httpClient,
                "POST",
                url,
                body,
                contentType,
                timeout,
                headers,
                targetAddressValidator,
                rateLimit);
      } catch (TargetAddressValidator.TargetAddressBlockedException e) {
        throw new DropboxApiException(DropboxApiException.Kind.BLOCKED, 0, null, e.getMessage());
      } catch (RedirectFollowingFetcher.RedirectRejectedException e) {
        throw new DropboxApiException(
            DropboxApiException.Kind.FORBIDDEN,
            0,
            null,
            "Dropbox hat von einer fremden Adresse geantwortet; die Antwort wurde verworfen.");
      } catch (IOException e) {
        log.debug("Dropbox request failed: {}", e.getClass().getSimpleName());
        throw new DropboxApiException(
            DropboxApiException.Kind.TRANSIENT,
            0,
            null,
            ProxyRefusal.describe(e).orElse("Dropbox ist nicht erreichbar."));
      }
      int status = response.statusCode();
      if (status >= 200 && status < 300) {
        return new Answer(response);
      }
      String summary = errorSummary(response);
      if (status == 401
          && !summary.startsWith("missing_scope")
          && !renewed
          && renewedAfterRejection != null) {
        renewed = true;
        if (renewedAfterRejection.test(sent)) {
          continue;
        }
      }
      throw failure(status, summary);
    }
  }

  private static DropboxApiException failure(int status, String summary) {
    return switch (status) {
      case 401 ->
          summary.startsWith("missing_scope")
              ? new DropboxApiException(
                  DropboxApiException.Kind.SCOPE_MISSING,
                  status,
                  summary,
                  "Der Dropbox-App fehlt eine Berechtigung (files.metadata.read,"
                      + " files.content.read, account_info.read); die Quelle muss danach neu"
                      + " verbunden werden.")
              : new DropboxApiException(
                  DropboxApiException.Kind.UNAUTHORIZED,
                  status,
                  summary,
                  "Dropbox hat das Zugriffstoken abgewiesen.");
      case 409 -> {
        if (summary.startsWith("reset")) {
          yield new DropboxApiException(
              DropboxApiException.Kind.RESET,
              status,
              summary,
              "Dropbox nimmt den gespeicherten Stand des Änderungsprotokolls nicht mehr an.");
        }
        if (summary.contains("not_found")) {
          yield new DropboxApiException(
              DropboxApiException.Kind.NOT_FOUND,
              status,
              summary,
              "Dropbox kennt den Pfad nicht oder zeigt ihn dem Konto nicht.");
        }
        yield new DropboxApiException(
            DropboxApiException.Kind.CONFLICT,
            status,
            summary,
            summary.contains("restricted_content")
                ? "Dropbox sperrt diesen Inhalt für das Konto."
                : "Dropbox lehnt die Anfrage für diesen Pfad ab.");
      }
      case 403 ->
          new DropboxApiException(
              DropboxApiException.Kind.FORBIDDEN,
              status,
              summary,
              "Dropbox verweigert dem Konto den Zugriff.");
      case 400 ->
          new DropboxApiException(
              DropboxApiException.Kind.BAD_REQUEST,
              status,
              summary,
              "Dropbox hat die Anfrage als ungültig abgewiesen.");
      case 429 ->
          new DropboxApiException(
              DropboxApiException.Kind.TRANSIENT,
              status,
              summary,
              "Dropbox drosselt die Anfragen weiterhin; die Datei folgt im nächsten Lauf.");
      default ->
          new DropboxApiException(
              DropboxApiException.Kind.TRANSIENT,
              status,
              summary,
              ProxyRefusal.statusMessage(
                  status, "Dropbox hat mit HTTP " + status + " geantwortet."));
    };
  }

  /**
   * The {@code error_summary} of a Dropbox error body without its trailing path-free counter, or
   * the {@code .tag} of {@code error}; {@code ""} for none. A plain-text body counts as none.
   */
  private static String errorSummary(HttpResponse<InputStream> response) {
    try (InputStream body = response.body()) {
      JsonNode root = JSON.readTree(BoundedStreams.readFully(body, MAX_ERROR_BYTES));
      if (root == null || !root.isObject()) {
        return "";
      }
      JsonNode summary = root.get("error_summary");
      if (summary != null && summary.isString()) {
        return summary.asString();
      }
      JsonNode error = root.get("error");
      JsonNode tag = error == null ? null : error.get(".tag");
      return tag != null && tag.isString() ? tag.asString() : "";
    } catch (IOException | JacksonException e) {
      return "";
    }
  }

  /**
   * {@code value} as JSON with every character outside ASCII escaped, as an HTTP header such as
   * {@code Dropbox-API-Arg} must carry it.
   */
  static String asciiJson(Object value) {
    String json = new String(JSON.writeValueAsBytes(value), StandardCharsets.UTF_8);
    StringBuilder ascii = new StringBuilder(json.length());
    for (int i = 0; i < json.length(); i++) {
      char c = json.charAt(i);
      if (c < 0x7f) {
        ascii.append(c);
      } else {
        ascii.append(String.format("\\u%04x", (int) c));
      }
    }
    return ascii.toString();
  }

  private static void deleteQuietly(Path file) {
    if (file != null) {
      try {
        Files.deleteIfExists(file);
      } catch (IOException e) {
        log.warn("Failed to delete temp file {}", file);
      }
    }
  }
}
