package io.opaa.indexing.source.googledrive;

import io.opaa.indexing.source.RequestBudget;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.BoundedStreams;
import io.opaa.sourceaccess.RateLimitHandling;
import io.opaa.sourceaccess.RateLimitPolicy;
import io.opaa.sourceaccess.RedirectFollowingFetcher;
import io.opaa.sourceaccess.RedirectFollowingFetcher.RedirectPolicy;
import io.opaa.sourceaccess.Sleeper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Drive REST API v3 as the connector uses it (ADR-0040, Entscheidung 11): every call through
 * {@link RedirectFollowingFetcher} with {@code REJECT_OFF_ORIGIN}, the access token asked anew per
 * request, every attempt charged to the run's {@link RequestBudget}. A throttle ({@code 429},
 * {@code 403 rateLimitExceeded}/{@code userRateLimitExceeded}) is waited out with exponential
 * backoff and retried (Entscheidung 10). A rejected token ({@code 401}) is retried once when the
 * run's credentials hold a renewed one; every other failure becomes a {@link DriveApiException}.
 */
final class DriveApi {

  private static final Logger log = LoggerFactory.getLogger(DriveApi.class);

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final long MAX_JSON_BYTES = 16L * 1024 * 1024;

  private final URI apiBase;
  private final Supplier<String> token;
  private final Predicate<String> renewedAfterRejection;
  private final HttpClient httpClient;
  private final TargetAddressValidator targetAddressValidator;
  private final Duration timeout;
  private final Duration downloadTimeout;
  private final RequestBudget budget;
  private final int maxRetries;
  private final Duration backoff;
  private final Sleeper sleeper;

  DriveApi(
      URI apiBase,
      Supplier<String> token,
      Predicate<String> renewedAfterRejection,
      HttpClient httpClient,
      TargetAddressValidator targetAddressValidator,
      Duration timeout,
      Duration downloadTimeout,
      RequestBudget budget,
      int maxRetries,
      Duration backoff,
      Sleeper sleeper) {
    this.apiBase = apiBase;
    this.token = token;
    this.renewedAfterRejection = renewedAfterRejection;
    this.httpClient = httpClient;
    this.targetAddressValidator = targetAddressValidator;
    this.timeout = timeout;
    this.downloadTimeout = downloadTimeout;
    this.budget = budget;
    this.maxRetries = Math.max(0, maxRetries);
    this.backoff = backoff;
    this.sleeper = sleeper;
  }

  RequestBudget budget() {
    return budget;
  }

  /** {@code GET <base>/drive/v3/<path>?<query>} answered with a JSON object. */
  JsonNode get(String path, Map<String, String> query)
      throws DriveApiException, InterruptedException {
    try (Answer answer = send(path, query)) {
      byte[] bytes = BoundedStreams.readFully(answer.response().body(), MAX_JSON_BYTES);
      return JSON.readTree(bytes);
    } catch (JacksonException | IOException e) {
      throw new DriveApiException(
          DriveApiException.Kind.TRANSIENT, 200, "Google Drive hat unlesbar geantwortet.");
    }
  }

  /**
   * The body of {@code GET <path>?<query>} - a download or an export - in a temp file <b>the caller
   * deletes</b>, at most {@code maxBytes} and within the download timeout counted from the answer's
   * start; an overrun is {@code TRANSIENT} and leaves no temp file.
   */
  Path download(String path, Map<String, String> query, long maxBytes)
      throws DriveApiException, InterruptedException {
    Path temp = null;
    try (Answer answer = send(path, query)) {
      long deadline = System.nanoTime() + downloadTimeout.toNanos();
      temp = Files.createTempFile("opaa-gdrive-", ".bin");
      try (InputStream in = answer.response().body();
          OutputStream out = Files.newOutputStream(temp)) {
        BoundedStreams.copyBefore(in, out, maxBytes, deadline);
      }
      budget.meter().recordBytes(Files.size(temp));
      return temp;
    } catch (BoundedStreams.LimitExceededException e) {
      deleteQuietly(temp);
      throw new DriveApiException(
          DriveApiException.Kind.TOO_LARGE,
          200,
          "Die Datei ist größer als " + maxBytes + " Bytes.");
    } catch (IOException e) {
      deleteQuietly(temp);
      throw new DriveApiException(
          DriveApiException.Kind.TRANSIENT, 200, "Die Übertragung von Google Drive brach ab.");
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
  private Answer send(String path, Map<String, String> query)
      throws DriveApiException, InterruptedException {
    String url = apiBase.resolve("/drive/v3/" + path) + queryString(query);
    boolean renewed = false;
    for (int attempt = 0; ; attempt++) {
      HttpResponse<InputStream> response;
      String sent = token.get();
      try {
        response =
            RedirectFollowingFetcher.sendFollowingRedirects(
                httpClient,
                url,
                timeout,
                Map.of("Authorization", "Bearer " + sent, "Accept", "application/json"),
                targetAddressValidator,
                RedirectPolicy.REJECT_OFF_ORIGIN,
                new RateLimitHandling(RateLimitPolicy.NONE, sleeper, budget));
      } catch (TargetAddressValidator.TargetAddressBlockedException e) {
        throw new DriveApiException(DriveApiException.Kind.BLOCKED, 0, e.getMessage());
      } catch (RedirectFollowingFetcher.RedirectRejectedException e) {
        // deterministic: the same request is refused the same way every time
        throw new DriveApiException(
            DriveApiException.Kind.FORBIDDEN,
            0,
            "Google Drive hat auf eine fremde Adresse weitergeleitet; die Anfrage wurde nicht"
                + " gesendet.");
      } catch (IOException e) {
        log.debug("Drive request to {} failed: {}", path, e.getClass().getSimpleName());
        throw new DriveApiException(
            DriveApiException.Kind.TRANSIENT, 0, "Google Drive ist nicht erreichbar.");
      }
      int status = response.statusCode();
      if (status >= 200 && status < 300) {
        return new Answer(response);
      }
      if (status == 401 && !renewed && renewedAfterRejection != null) {
        renewed = true;
        if (renewedAfterRejection.test(sent)) {
          new Answer(response).close();
          continue;
        }
      }
      String reason = reason(response);
      boolean throttled =
          status == 429
              || (status == 403
                  && ("rateLimitExceeded".equals(reason)
                      || "userRateLimitExceeded".equals(reason)));
      if (throttled && attempt < maxRetries) {
        Duration wait = backoff.multipliedBy(1L << Math.min(attempt, 10));
        budget.throttled(status, wait);
        sleeper.sleep(wait);
        continue;
      }
      throw failure(status, reason, throttled).withReason(reason);
    }
  }

  private static DriveApiException failure(int status, String reason, boolean throttled) {
    if (throttled) {
      return new DriveApiException(
          DriveApiException.Kind.TRANSIENT,
          status,
          "Google Drive drosselt die Anfragen weiterhin; die Datei folgt im nächsten Lauf.");
    }
    return switch (status) {
      case 401 ->
          new DriveApiException(
              DriveApiException.Kind.UNAUTHORIZED,
              status,
              "Google Drive hat das Zugriffstoken abgewiesen.");
      case 404 ->
          new DriveApiException(
              DriveApiException.Kind.NOT_FOUND,
              status,
              "Google Drive kennt die Datei nicht oder zeigt sie dem Konto nicht.");
      case 403 ->
          switch (reason == null ? "" : reason) {
            case "dailyLimitExceeded" ->
                new DriveApiException(
                    DriveApiException.Kind.DAILY_LIMIT,
                    status,
                    "Das Tageskontingent der Drive-API ist erschöpft; der nächste geplante Lauf"
                        + " setzt an.");
            case "insufficientPermissions", "ACCESS_TOKEN_SCOPE_INSUFFICIENT" ->
                new DriveApiException(
                    DriveApiException.Kind.SCOPE_MISSING,
                    status,
                    "Dem Zugriffstoken fehlt der Scope drive.readonly; bei Delegation muss er in"
                        + " der Admin-Konsole freigegeben sein.");
            case "exportSizeLimitExceeded" ->
                new DriveApiException(
                    DriveApiException.Kind.EXPORT_LIMIT,
                    status,
                    "Die Datei überschreitet die Exportgrenze von Google Drive (10 MB).");
            default ->
                new DriveApiException(
                    DriveApiException.Kind.FORBIDDEN,
                    status,
                    "Google Drive verweigert den Zugriff"
                        + (reason == null ? "." : " (" + reason + ")."));
          };
      default ->
          new DriveApiException(
              DriveApiException.Kind.TRANSIENT,
              status,
              "Google Drive hat mit HTTP " + status + " geantwortet.");
    };
  }

  /** The first {@code errors[].reason} of a Google error body, {@code null} when there is none. */
  private static String reason(HttpResponse<InputStream> response) {
    try (InputStream body = response.body()) {
      JsonNode root = JSON.readTree(BoundedStreams.readFully(body, 64 * 1024));
      JsonNode error = root == null ? null : root.get("error");
      if (error == null) {
        return null;
      }
      JsonNode errors = error.get("errors");
      if (errors != null && errors.isArray() && !errors.isEmpty()) {
        JsonNode reason = errors.get(0).get("reason");
        if (reason != null && reason.isString()) {
          return reason.asString();
        }
      }
      JsonNode details = error.get("details");
      if (details != null && details.isArray()) {
        for (JsonNode detail : details) {
          JsonNode reason = detail.get("reason");
          if (reason != null && reason.isString()) {
            return reason.asString();
          }
        }
      }
      return null;
    } catch (IOException | JacksonException e) {
      return null;
    }
  }

  private static String queryString(Map<String, String> query) {
    if (query.isEmpty()) {
      return "";
    }
    StringBuilder text = new StringBuilder("?");
    query.forEach(
        (key, value) -> {
          if (text.length() > 1) {
            text.append('&');
          }
          text.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
    return text.toString();
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
