package io.opaa.indexing.source.confluence;

import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.RedirectFollowingFetcher;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one place both adapters send requests through: adds {@code Authorization}, {@code Accept} and
 * {@code User-Agent}, follows redirects under the shared target validation, honours {@code 429}/
 * {@code Retry-After} by waiting and retrying, bounds every response body, and maps every failure
 * to a {@link ConfluenceAccessException} whose message names resource and status but never a header
 * value or a raw upstream body.
 */
final class ConfluenceHttp {

  private static final Logger log = LoggerFactory.getLogger(ConfluenceHttp.class);

  /** Wait applied to a {@code 429} without a usable {@code Retry-After}. */
  static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(5);

  /**
   * Appended to the target-validation rejection so whoever configures an on-premises instance
   * learns which setting unblocks it (ADR-0023: private address ranges are rejected by default).
   */
  static final String ALLOWLIST_HINT =
      "Interne Adressen gibt der Betrieb über OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST frei.";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpClient httpClient;
  private final ConfluenceConnection connection;
  private final ConfluenceProperties properties;
  private final Duration requestTimeout;
  private final TargetAddressValidator targetAddressValidator;
  private final BoundedDownloader downloader;
  private final Sleeper sleeper;
  private final ConfluenceRequestMeter meter;

  ConfluenceHttp(
      HttpClient httpClient,
      ConfluenceConnection connection,
      ConfluenceProperties properties,
      TargetAddressValidator targetAddressValidator,
      Sleeper sleeper,
      ConfluenceRequestMeter meter) {
    this(httpClient, connection, properties, targetAddressValidator, sleeper, meter, null);
  }

  /** {@code requestTimeout} overrides the configured per-request timeout when non-null. */
  ConfluenceHttp(
      HttpClient httpClient,
      ConfluenceConnection connection,
      ConfluenceProperties properties,
      TargetAddressValidator targetAddressValidator,
      Sleeper sleeper,
      ConfluenceRequestMeter meter,
      Duration requestTimeout) {
    this.requestTimeout = requestTimeout == null ? properties.requestTimeout() : requestTimeout;
    this.httpClient = httpClient;
    this.connection = connection;
    this.properties = properties;
    this.targetAddressValidator = targetAddressValidator;
    this.downloader = new BoundedDownloader(targetAddressValidator);
    this.sleeper = sleeper;
    this.meter = meter;
  }

  /** A raw response: status and a bounded body. */
  record Response(int status, byte[] body) {
    String bodyText() {
      return new String(body, StandardCharsets.UTF_8);
    }
  }

  /**
   * GET {@code url} as JSON; {@code resource} is the German noun phrase failure messages name (e.g.
   * "die Seite 123").
   */
  JsonNode getJson(String url, String resource)
      throws ConfluenceAccessException, InterruptedException {
    Response response = get(url, resource);
    if (response.status() != 200) {
      throw failure(response.status(), resource);
    }
    return parse(response, resource);
  }

  /** Like {@link #getJson}, but a {@code 404} is a regular, empty outcome. */
  Optional<JsonNode> getJsonOrNotFound(String url, String resource)
      throws ConfluenceAccessException, InterruptedException {
    Response response = get(url, resource);
    if (response.status() == 404) {
      return Optional.empty();
    }
    if (response.status() != 200) {
      throw failure(response.status(), resource);
    }
    return Optional.of(parse(response, resource));
  }

  /**
   * GET {@code url} and return whatever came back (after rate-limit retries) - for probes that read
   * the status themselves. Connection-level failures still become {@link
   * ConfluenceAccessException}.
   */
  Response get(String url, String resource) throws ConfluenceAccessException, InterruptedException {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Accept", "application/json");
    headers.put("User-Agent", properties.userAgent());
    if (connection.credentials() != null) {
      headers.put("Authorization", connection.credentials().authorizationHeader());
    }
    int attempt = 0;
    while (true) {
      meter.recordRequest();
      HttpResponse<InputStream> response;
      try {
        response =
            RedirectFollowingFetcher.sendFollowingRedirects(
                httpClient,
                url,
                requestTimeout,
                headers,
                targetAddressValidator,
                RedirectFollowingFetcher.RedirectPolicy.REJECT_OFF_ORIGIN);
      } catch (IOException e) {
        throw connectionFailure(e, resource);
      }
      byte[] body;
      try (InputStream in = response.body()) {
        body = in.readNBytes(boundedResponseBytes());
      } catch (IOException e) {
        throw connectionFailure(e, resource);
      }
      if (!isThrottled(response.statusCode())) {
        return new Response(response.statusCode(), body);
      }
      attempt++;
      if (attempt > properties.maxRateLimitRetries()) {
        throw new ConfluenceAccessException.RateLimited(
            "Confluence begrenzt die Anfragerate (HTTP "
                + response.statusCode()
                + "); nach "
                + properties.maxRateLimitRetries()
                + " Wartezyklen für "
                + resource
                + " aufgegeben.");
      }
      Duration wait = retryAfter(response);
      log.info(
          "Confluence rate limit (HTTP {}) for {} - waiting {} before retry {}/{}",
          response.statusCode(),
          connection.baseUrl().getHost(),
          wait,
          attempt,
          properties.maxRateLimitRetries());
      meter.recordThrottle(wait);
      sleeper.sleep(wait);
    }
  }

  /**
   * Downloads {@code url} into a temporary file bounded by {@code maxBytes}. Off-origin redirects
   * (Cloud serves attachment bytes from its media service) are followed with {@code Authorization}
   * dropped - the redirect target is a pre-signed address that needs no credentials, and the token
   * must never travel to a host the library owner did not configure.
   */
  BoundedDownloader.DownloadedFile download(String url, String fileName, long maxBytes)
      throws ConfluenceAccessException, InterruptedException {
    String authHeader =
        connection.credentials() == null ? null : connection.credentials().authorizationHeader();
    try {
      return downloader.downloadBounded(
          httpClient,
          url,
          fileName,
          maxBytes,
          properties.userAgent(),
          authHeader,
          RedirectFollowingFetcher.RedirectPolicy.DROP_AUTHORIZATION_OFF_ORIGIN);
    } catch (BoundedDownloader.AttachmentTooLargeException e) {
      throw e;
    } catch (BoundedDownloader.HttpStatusException e) {
      throw failure(e.statusCode(), "der Anhang " + fileName);
    } catch (IOException e) {
      throw connectionFailure(e, "der Anhang " + fileName);
    }
  }

  ConfluenceConnection connection() {
    return connection;
  }

  /**
   * The proxy is as caller-controlled as the target and decides where the TCP connection - and the
   * credentials on it - actually go; it passes the same address-range check, with the same
   * allowlist hint.
   */
  static void validateProxy(TargetAddressValidator validator, String proxyHost)
      throws ConfluenceAccessException {
    try {
      validator.validateHost(proxyHost);
    } catch (IOException e) {
      throw new ConfluenceAccessException(e.getMessage() + " " + ALLOWLIST_HINT, e);
    }
  }

  ConfluenceRequestMeter meter() {
    return meter;
  }

  ConfluenceProperties properties() {
    return properties;
  }

  private int boundedResponseBytes() {
    return (int) Math.min(Integer.MAX_VALUE, properties.maxResponseBytes());
  }

  private static boolean isThrottled(int status) {
    return status == 429;
  }

  JsonNode parse(Response response, String resource) throws ConfluenceAccessException {
    try {
      return JSON.readTree(response.body());
    } catch (JacksonException e) {
      throw new ConfluenceAccessException(
          "Confluence lieferte für " + resource + " keine gültige JSON-Antwort.");
    }
  }

  /** Maps a non-success status to the exception the caller can act on. */
  ConfluenceAccessException failure(int status, String resource) {
    return switch (status) {
      case 401 ->
          new ConfluenceAccessException.Authentication(
              "Confluence hat die Zugangsdaten abgelehnt (HTTP 401). Prüfen Sie Token und, bei"
                  + " Cloud, die E-Mail-Adresse.");
      case 403 ->
          new ConfluenceAccessException.Forbidden(
              "Keine Leseberechtigung für " + resource + " (HTTP 403).");
      case 404 ->
          new ConfluenceAccessException.NotFound(
              "Confluence kennt " + resource + " nicht (HTTP 404).");
      default ->
          new ConfluenceAccessException(
              "Confluence antwortete für " + resource + " mit HTTP " + status + ".");
    };
  }

  private ConfluenceAccessException connectionFailure(IOException e, String resource) {
    String host = connection.baseUrl().getHost();
    if (e instanceof TargetAddressValidator.TargetAddressBlockedException) {
      return new ConfluenceAccessException(e.getMessage() + " " + ALLOWLIST_HINT, e);
    }
    if (e instanceof RedirectFollowingFetcher.RedirectRejectedException rejected) {
      return new ConfluenceAccessException(
          rejected.userMessage() + " (beim Abruf von " + resource + ").", e);
    }
    if (e instanceof HttpTimeoutException) {
      return new ConfluenceAccessException(
          "Confluence unter " + host + " hat nicht rechtzeitig geantwortet (" + resource + ").", e);
    }
    if (e instanceof ConnectException) {
      return new ConfluenceAccessException(
          "Confluence unter " + host + " ist nicht erreichbar (Verbindung abgelehnt).", e);
    }
    if (e instanceof SSLException) {
      return new ConfluenceAccessException(
          "TLS-Verbindung zu " + host + " fehlgeschlagen (Zertifikat nicht vertrauenswürdig?).", e);
    }
    return new ConfluenceAccessException(
        "Confluence unter "
            + host
            + " ist nicht erreichbar ("
            + e.getClass().getSimpleName()
            + ").",
        e);
  }

  /** {@code Retry-After} as seconds or HTTP-date, defaulted and capped by configuration. */
  Duration retryAfter(HttpResponse<?> response) {
    Duration wait =
        response
            .headers()
            .firstValue("Retry-After")
            .map(ConfluenceHttp::parseRetryAfter)
            .orElse(null);
    if (wait == null || wait.isNegative() || wait.isZero()) {
      wait = DEFAULT_RETRY_AFTER;
    }
    if (wait.compareTo(properties.maxRetryAfter()) > 0) {
      wait = properties.maxRetryAfter();
    }
    return wait;
  }

  static Duration parseRetryAfter(String value) {
    String trimmed = value.strip();
    try {
      return Duration.ofSeconds(Long.parseLong(trimmed));
    } catch (NumberFormatException ignored) {
      // not a delta-seconds value; try HTTP-date
    }
    try {
      Instant at = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
      return Duration.between(Instant.now(), at);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /**
   * Resolves a link the instance handed out ({@code _links.next}, an attachment's download link)
   * against the connection's base URL and refuses anything that leaves the instance's own origin:
   * every request this class sends carries the credentials, so a link to another host - from a
   * compromised or spoofed instance - would carry them there too.
   *
   * @param contextRelativeLink an already context-prefixed relative path (starting with {@code /})
   *     or an absolute URL
   */
  String resolveLink(String contextRelativeLink, String resource) throws ConfluenceAccessException {
    URI uri;
    try {
      uri = URI.create(contextRelativeLink);
    } catch (IllegalArgumentException e) {
      throw new ConfluenceAccessException(
          "Confluence lieferte für " + resource + " einen ungültigen Verweis.");
    }
    URI resolved =
        uri.isAbsolute()
            ? uri
            : URI.create(
                connection.url(
                    contextRelativeLink.startsWith("/")
                        ? contextRelativeLink
                        : "/" + contextRelativeLink));
    if (!RedirectFollowingFetcher.sameOrigin(connection.baseUrl(), resolved)) {
      throw new ConfluenceAccessException(
          "Confluence verweist für "
              + resource
              + " auf einen fremden Host ("
              + resolved.getHost()
              + "); der Abruf wurde abgelehnt, weil Zugangsdaten nur an die konfigurierte Adresse"
              + " gehen.");
    }
    return resolved.toString();
  }

  /**
   * Guards one listing against a server whose {@code next} never runs out: a repeated URL or more
   * than {@link ConfluenceProperties#maxListingPages} pages abandon the listing visibly.
   */
  final class ListingGuard {
    private final String resource;
    private final java.util.Set<String> seen = new java.util.HashSet<>();

    ListingGuard(String resource) {
      this.resource = resource;
    }

    void visit(String url) throws ConfluenceAccessException {
      if (!seen.add(url)) {
        throw new ConfluenceAccessException(
            "Confluence wiederholt beim Blättern durch "
                + resource
                + " dieselbe Seite; die Auflistung wurde abgebrochen.");
      }
      if (seen.size() > properties.maxListingPages()) {
        throw new ConfluenceAccessException(
            "Die Auflistung von "
                + resource
                + " umfasst mehr als "
                + properties.maxListingPages()
                + " Seiten und wurde abgebrochen (opaa.indexing.confluence.max-listing-pages).");
      }
    }
  }
}
