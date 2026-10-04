package io.opaa.msgraph;

import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.BoundedStreams;
import io.opaa.sourceaccess.RateLimitHandling;
import io.opaa.sourceaccess.RateLimitPolicy;
import io.opaa.sourceaccess.RedirectFollowingFetcher;
import io.opaa.sourceaccess.RedirectFollowingFetcher.RedirectPolicy;
import io.opaa.sourceaccess.SourceRequestMeter;
import io.opaa.sourceaccess.SourceRequestPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Microsoft Graph v1.0 at {@code origin}, every request through {@link RedirectFollowingFetcher}: a
 * JSON read refuses an off-origin redirect; a download follows one only to {@code https} and
 * without {@code Authorization}, the target validated on every hop and named in no log or message.
 * The token is asked anew per attempt, a {@code 401} retried once when the caller holds a renewed
 * one. {@code 429} and {@code 503} are waited out under the {@link RateLimitPolicy}; the listener
 * is told of every attempt and every wait. A page link is honoured only as a token on Graph's own
 * origin - the address is always built here.
 */
public final class GraphClient {

  /** Default bound of one JSON answer. */
  public static final long DEFAULT_MAX_JSON_BYTES = 16L * 1024 * 1024;

  private static final Logger log = LoggerFactory.getLogger(GraphClient.class);

  private static final String VERSION_PATH = "/v1.0/";
  private static final long MAX_ERROR_BYTES = 64L * 1024;
  private static final Pattern ERROR_CODE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
  private static final Pattern DELTA_FUNCTION = Pattern.compile("\\(token='?([^')]+)'?\\)$");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final URI origin;
  private final Supplier<String> token;
  private final Predicate<String> renewedAfterRejection;
  private final HttpClient httpClient;
  private final TargetAddressValidator targetAddressValidator;
  private final RateLimitHandling rateLimit;
  private final SourceRequestMeter meter;
  private final Duration timeout;
  private final long maxJsonBytes;

  /**
   * @param origin scheme, host and port of Graph, such as {@code https://graph.microsoft.com}
   * @param renewedAfterRejection whether a token newer than the rejected one is available; {@code
   *     null} for never
   * @param rateLimit how throttled answers are waited out, and the listener every attempt is
   *     charged to
   * @param meter receives the downloaded bytes; requests are counted by the listener
   * @param timeout bounds each attempt, the reading of a JSON answer included
   */
  public GraphClient(
      URI origin,
      Supplier<String> token,
      Predicate<String> renewedAfterRejection,
      HttpClient httpClient,
      TargetAddressValidator targetAddressValidator,
      RateLimitHandling rateLimit,
      SourceRequestMeter meter,
      Duration timeout,
      long maxJsonBytes) {
    this.origin = URI.create(origin.getScheme() + "://" + origin.getRawAuthority());
    this.token = Objects.requireNonNull(token, "token");
    this.renewedAfterRejection =
        renewedAfterRejection == null ? rejected -> false : renewedAfterRejection;
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    this.targetAddressValidator =
        Objects.requireNonNull(targetAddressValidator, "targetAddressValidator");
    this.rateLimit = Objects.requireNonNull(rateLimit, "rateLimit");
    this.meter = Objects.requireNonNull(meter, "meter");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    if (maxJsonBytes <= 0) {
      throw new IllegalArgumentException("maxJsonBytes must be positive, got " + maxJsonBytes);
    }
    this.maxJsonBytes = maxJsonBytes;
  }

  /** {@code GET /v1.0/<path>?<query>}, answered with a JSON object. */
  public JsonNode get(String path, Map<String, String> query)
      throws GraphException, InterruptedException {
    Answer answer = send(url(path, query), RedirectPolicy.REJECT_OFF_ORIGIN, "application/json");
    int status = answer.response().statusCode();
    try (InputStream body = answer.response().body()) {
      JsonNode root =
          JSON.readTree(BoundedStreams.readFullyBefore(body, maxJsonBytes, answer.deadline()));
      if (root == null || !root.isObject()) {
        throw unreadable(status);
      }
      return root;
    } catch (BoundedStreams.LimitExceededException e) {
      throw new GraphException(
          GraphException.Kind.TRANSIENT,
          status,
          null,
          "Die Antwort von Microsoft Graph ist größer als " + maxJsonBytes + " Bytes.");
    } catch (HttpTimeoutException e) {
      throw new GraphException(
          GraphException.Kind.TRANSIENT,
          status,
          null,
          "Microsoft Graph hat nicht innerhalb von " + timeout.toSeconds() + " s geantwortet.");
    } catch (IOException | JacksonException e) {
      throw unreadable(status);
    }
  }

  /**
   * One page of the collection at {@code path}: the first for a {@code null} {@code pageToken},
   * else the one a {@link GraphPage#nextToken()} or {@link GraphPage#deltaToken()} of the same
   * {@code path} names. A {@code delta} path carries the token as {@code token} ({@code latest}
   * asks for the current delta token alone), every other path as {@code $skiptoken}.
   */
  public GraphPage page(String path, Map<String, String> query, String pageToken)
      throws GraphException, InterruptedException {
    Map<String, String> withToken = new LinkedHashMap<>(query);
    if (pageToken != null) {
      withToken.put(tokenParameter(path), pageToken);
    }
    JsonNode root = get(path, withToken);
    JsonNode value = root.get("value");
    if (value == null || !value.isArray()) {
      throw unreadable(200);
    }
    List<JsonNode> entries = new ArrayList<>();
    for (JsonNode entry : value) {
      entries.add(entry);
    }
    return new GraphPage(
        entries,
        linkToken(root, "@odata.nextLink", path),
        linkToken(root, "@odata.deltaLink", path));
  }

  /**
   * The body of {@code GET /v1.0/<path>} - typically {@code drives/<id>/items/<id>/content} - in a
   * temp file <b>the caller deletes</b>, at most {@code maxBytes}.
   */
  public Path download(String path, long maxBytes) throws GraphException, InterruptedException {
    Answer answer =
        send(url(path, Map.of()), RedirectPolicy.DROP_AUTHORIZATION_HTTPS_ONLY_OFF_ORIGIN, "*/*");
    Path temp = null;
    boolean kept = false;
    try (InputStream body = answer.response().body()) {
      if (declaredLength(answer.response()) > maxBytes) {
        throw tooLarge(maxBytes);
      }
      temp = Files.createTempFile("opaa-msgraph-", ".bin");
      try (OutputStream out = Files.newOutputStream(temp)) {
        BoundedStreams.copy(body, out, maxBytes);
      }
      meter.recordBytes(Files.size(temp));
      kept = true;
      return temp;
    } catch (BoundedStreams.LimitExceededException e) {
      throw tooLarge(maxBytes);
    } catch (IOException e) {
      log.debug("Microsoft Graph download broke off: {}", e.getClass().getSimpleName());
      throw new GraphException(
          GraphException.Kind.TRANSIENT,
          answer.response().statusCode(),
          null,
          "Die Übertragung von Microsoft Graph brach ab.");
    } finally {
      if (!kept) {
        deleteQuietly(temp);
      }
    }
  }

  /** A successful answer and the deadline its body must be read by. */
  private record Answer(HttpResponse<InputStream> response, long deadline) {}

  private Answer send(String url, RedirectPolicy policy, String accept)
      throws GraphException, InterruptedException {
    RateLimitPolicy throttling = rateLimit.policy();
    boolean renewed = false;
    int retries = 0;
    while (true) {
      String sent = token.get();
      long deadline = System.nanoTime() + timeout.toNanos();
      HttpResponse<InputStream> response = attempt(url, policy, sent, accept);
      int status = response.statusCode();
      if (status >= 200 && status < 300) {
        return new Answer(response, deadline);
      }
      boolean fromGraph = RedirectFollowingFetcher.authorizationSent(response);
      if (status == 401 && fromGraph && !renewed) {
        renewed = true;
        if (renewedAfterRejection.test(sent)) {
          closeQuietly(response);
          continue;
        }
      }
      String code = errorCode(response, deadline);
      if ((status == 429 || status == 503) && retries < throttling.maxRetries()) {
        retries++;
        Duration wait = throttling.waitFor(response);
        log.info(
            "Microsoft Graph throttled (HTTP {}) - waiting {} before retry {}/{}",
            status,
            wait,
            retries,
            throttling.maxRetries());
        rateLimit.listener().throttled(status, wait);
        rateLimit.sleeper().sleep(wait);
        continue;
      }
      throw failure(status, fromGraph ? code : null, fromGraph);
    }
  }

  private HttpResponse<InputStream> attempt(
      String url, RedirectPolicy policy, String sent, String accept)
      throws GraphException, InterruptedException {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Authorization", "Bearer " + sent);
    headers.put("Accept", accept);
    headers.put("User-Agent", SourceRequestPolicy.DEFAULT_USER_AGENT);
    try {
      return RedirectFollowingFetcher.sendFollowingRedirects(
          httpClient,
          url,
          timeout,
          headers,
          targetAddressValidator,
          policy,
          new RateLimitHandling(RateLimitPolicy.NONE, rateLimit.sleeper(), rateLimit.listener()));
    } catch (TargetAddressValidator.UnknownTargetHostException e) {
      throw new GraphException(GraphException.Kind.TRANSIENT, 0, null, e.getMessage());
    } catch (TargetAddressValidator.TargetAddressBlockedException e) {
      throw new GraphException(
          GraphException.Kind.BLOCKED,
          0,
          null,
          e.getMessage() + " " + TargetAddressValidator.ALLOWLIST_HINT);
    } catch (RedirectFollowingFetcher.RedirectRejectedException e) {
      throw new GraphException(GraphException.Kind.BLOCKED, 0, null, e.userMessage() + ".");
    } catch (IOException e) {
      log.debug("Microsoft Graph request failed: {}", e.getClass().getSimpleName());
      throw new GraphException(
          GraphException.Kind.TRANSIENT, 0, null, "Microsoft Graph ist nicht erreichbar.");
    }
  }

  private static GraphException failure(int status, String code, boolean fromGraph) {
    if (!fromGraph) {
      return new GraphException(
          GraphException.Kind.TRANSIENT,
          status,
          null,
          "Der Downloadserver von Microsoft Graph hat mit HTTP " + status + " geantwortet.");
    }
    String suffix = code == null ? "." : " (" + code + ").";
    return switch (status) {
      case 401 ->
          new GraphException(
              GraphException.Kind.UNAUTHORIZED,
              status,
              code,
              "Microsoft Graph hat das Zugriffstoken abgewiesen" + suffix);
      case 403 ->
          new GraphException(
              GraphException.Kind.FORBIDDEN,
              status,
              code,
              "Microsoft Graph verweigert der Anwendung den Zugriff" + suffix);
      case 404 ->
          new GraphException(
              GraphException.Kind.NOT_FOUND,
              status,
              code,
              "Microsoft Graph kennt das Element nicht oder zeigt es der Anwendung nicht" + suffix);
      case 410 ->
          new GraphException(
              GraphException.Kind.RESYNC,
              status,
              code,
              "Microsoft Graph verlangt einen Neuabgleich, die Änderungsmarke ist verfallen"
                  + suffix);
      case 429, 503 ->
          new GraphException(
              GraphException.Kind.TRANSIENT,
              status,
              code,
              "Microsoft Graph drosselt die Anfragen weiterhin" + suffix);
      default ->
          new GraphException(
              GraphException.Kind.TRANSIENT,
              status,
              code,
              "Microsoft Graph hat mit HTTP " + status + " geantwortet" + suffix);
    };
  }

  /**
   * Graph's {@code error.code}, reduced to a plain identifier; {@code null} when there is none.
   * Reads and closes the body.
   */
  private static String errorCode(HttpResponse<InputStream> response, long deadline) {
    try (InputStream body = response.body()) {
      JsonNode root =
          JSON.readTree(BoundedStreams.readFullyBefore(body, MAX_ERROR_BYTES, deadline));
      JsonNode code = root == null ? null : root.path("error").get("code");
      if (code == null || !code.isString()) {
        return null;
      }
      String text = code.asString();
      return ERROR_CODE.matcher(text).matches() ? text : null;
    } catch (IOException | JacksonException e) {
      return null;
    }
  }

  /**
   * The token of the page link in {@code field}, after checking that the link stays on this
   * client's origin under {@code /v1.0/}; {@code null} when the answer carries no such link. The
   * link's path is not compared with {@code path}: only the token is used, and the next address is
   * built from {@code path} again, its token encoded.
   */
  private String linkToken(JsonNode root, String field, String path) throws GraphException {
    JsonNode link = root.get(field);
    if (link == null || link.isNull()) {
      return null;
    }
    if (!link.isString()) {
      throw foreignLink();
    }
    URI uri;
    try {
      uri = new URI(link.asString());
    } catch (URISyntaxException e) {
      throw foreignLink();
    }
    if (!RedirectFollowingFetcher.sameOrigin(origin, uri)
        || uri.getRawUserInfo() != null
        || uri.getRawFragment() != null
        || uri.getPath() == null) {
      throw foreignLink();
    }
    if (!uri.getPath().regionMatches(true, 0, VERSION_PATH, 0, VERSION_PATH.length())) {
      throw foreignLink();
    }
    String parameter = tokenParameter(path);
    String token = queryParameter(uri.getRawQuery(), parameter);
    if (token == null && "token".equals(parameter)) {
      Matcher function = DELTA_FUNCTION.matcher(uri.getPath());
      token = function.find() ? function.group(1) : null;
    }
    if (token == null || token.isEmpty()) {
      throw foreignLink();
    }
    return token;
  }

  private static String tokenParameter(String path) {
    return relative(path).endsWith("/delta") ? "token" : "$skiptoken";
  }

  private static String queryParameter(String rawQuery, String name) {
    if (rawQuery == null) {
      return null;
    }
    for (String pair : rawQuery.split("&")) {
      int equals = pair.indexOf('=');
      if (equals > 0 && name.equals(decode(pair.substring(0, equals)))) {
        return decode(pair.substring(equals + 1));
      }
    }
    return null;
  }

  /** Percent-decoding only; a {@code +} stays a plus. */
  private static String decode(String raw) {
    return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
  }

  private String url(String path, Map<String, String> query) {
    String rawPath;
    try {
      rawPath = new URI(null, null, VERSION_PATH + relative(path), null).getRawPath();
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("not a Graph path: " + path, e);
    }
    StringBuilder url = new StringBuilder(origin.toString()).append(rawPath);
    char separator = '?';
    for (Map.Entry<String, String> parameter : query.entrySet()) {
      url.append(separator)
          .append(encode(parameter.getKey()).replace("%24", "$"))
          .append('=')
          .append(encode(parameter.getValue()));
      separator = '&';
    }
    return url.toString();
  }

  private static String encode(String text) {
    return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String relative(String path) {
    return path.startsWith("/") ? path.substring(1) : path;
  }

  private static long declaredLength(HttpResponse<InputStream> response) {
    try {
      return response.headers().firstValueAsLong("Content-Length").orElse(-1);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static GraphException unreadable(int status) {
    return new GraphException(
        GraphException.Kind.TRANSIENT, status, null, "Microsoft Graph hat unlesbar geantwortet.");
  }

  private static GraphException foreignLink() {
    return new GraphException(
        GraphException.Kind.BLOCKED,
        200,
        null,
        "Microsoft Graph hat auf eine Folgeseite an fremder Adresse oder ohne Seitenmarke"
            + " verwiesen.");
  }

  private static GraphException tooLarge(long maxBytes) {
    return new GraphException(
        GraphException.Kind.TOO_LARGE,
        200,
        null,
        "Die Datei ist größer als " + maxBytes + " Bytes.");
  }

  private static void closeQuietly(HttpResponse<InputStream> response) {
    try {
      response.body().close();
    } catch (IOException e) {
      // nothing left to read
    }
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
