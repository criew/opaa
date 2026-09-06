package io.opaa.indexing.source.confluence;

import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.RateLimitListener;
import io.opaa.sourceaccess.RateLimitPolicy;
import io.opaa.sourceaccess.RedirectFollowingFetcher;
import io.opaa.sourceaccess.SourceRequestPolicy;
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
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one place both adapters send requests through: adds {@code Authorization}, {@code Accept} and
 * the shared {@code User-Agent}, follows redirects under the shared target validation, waits out
 * {@code 429}/{@code Retry-After} under Confluence's own rate-limit numbers while charging every
 * attempt to the run's budget and meter, bounds every response body, and maps every failure to a
 * {@link ConfluenceAccessException} whose message names resource and status but never a header
 * value or a raw upstream body.
 */
final class ConfluenceHttp {

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
  private final SourceRequestPolicy requestPolicy;
  private final BoundedDownloader downloader;
  private final ConfluenceRequestMeter meter;
  private final int requestBudget;

  /**
   * Every attempt after a {@code 429} is a call to the instance like the first: charged to the
   * budget and counted on the meter, which also records the wait.
   */
  private final RateLimitListener budgetedRetries =
      new RateLimitListener() {
        @Override
        public void throttled(int statusCode, Duration wait) {
          meter.recordThrottle(wait);
        }

        @Override
        public void retrying() throws IOException {
          chargeBudget();
          meter.recordRequest();
        }
      };

  ConfluenceHttp(
      HttpClient httpClient,
      ConfluenceConnection connection,
      ConfluenceProperties properties,
      TargetAddressValidator targetAddressValidator,
      SourceRequestPolicy requestPolicy,
      ConfluenceRequestMeter meter) {
    this(httpClient, connection, properties, targetAddressValidator, requestPolicy, meter, null);
  }

  /** {@code requestTimeout} overrides the configured per-request timeout when non-null. */
  ConfluenceHttp(
      HttpClient httpClient,
      ConfluenceConnection connection,
      ConfluenceProperties properties,
      TargetAddressValidator targetAddressValidator,
      SourceRequestPolicy requestPolicy,
      ConfluenceRequestMeter meter,
      Duration requestTimeout) {
    this(
        httpClient,
        connection,
        properties,
        targetAddressValidator,
        requestPolicy,
        meter,
        requestTimeout,
        0);
  }

  /**
   * {@code requestBudget} &gt; 0 bounds the calls this client may make - set by {@link
   * ConfluenceClientFactory#createForRun} for a run's client, never for the wizard's probes and the
   * edition detection, which have no run to continue in. Counted are calls, not wire requests: a
   * redirect chain the fetcher follows counts once, a retry after a {@code 429} counts again.
   * {@code requestPolicy} contributes {@code User-Agent} and sleeper; the rate-limit numbers are
   * {@code properties}' own.
   */
  ConfluenceHttp(
      HttpClient httpClient,
      ConfluenceConnection connection,
      ConfluenceProperties properties,
      TargetAddressValidator targetAddressValidator,
      SourceRequestPolicy requestPolicy,
      ConfluenceRequestMeter meter,
      Duration requestTimeout,
      int requestBudget) {
    this.requestBudget = requestBudget;
    this.requestTimeout = requestTimeout == null ? properties.requestTimeout() : requestTimeout;
    this.httpClient = httpClient;
    this.connection = connection;
    this.properties = properties;
    this.targetAddressValidator = targetAddressValidator;
    this.requestPolicy =
        requestPolicy.withRateLimit(
            RateLimitPolicy.of(properties.maxRateLimitRetries(), properties.maxRetryAfter()));
    this.downloader = new BoundedDownloader(targetAddressValidator, this.requestPolicy);
    this.meter = meter;
  }

  private void chargeBudget() throws ConfluenceAccessException.BudgetExhausted {
    if (requestBudget > 0 && meter.requests() >= requestBudget) {
      throw new ConfluenceAccessException.BudgetExhausted(requestBudget);
    }
  }

  private String authorizationHeader() {
    return connection.credentials() == null ? null : connection.credentials().authorizationHeader();
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
   * ConfluenceAccessException}; a {@code 429} that outlasted every retry becomes {@link
   * ConfluenceAccessException.RateLimited}.
   */
  Response get(String url, String resource) throws ConfluenceAccessException, InterruptedException {
    Map<String, String> headers = requestPolicy.headers(authorizationHeader());
    headers.put("Accept", "application/json");
    // the budget counts every call, retries after a 429 included (budgetedRetries) - the meter is
    // per client, a client is per run, so this is the run's bound.
    chargeBudget();
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
              RedirectFollowingFetcher.RedirectPolicy.REJECT_OFF_ORIGIN,
              requestPolicy.rateLimitHandling(budgetedRetries));
    } catch (ConfluenceAccessException e) {
      throw e;
    } catch (IOException e) {
      throw connectionFailure(e, resource);
    }
    byte[] body;
    try (InputStream in = response.body()) {
      body = in.readNBytes(boundedResponseBytes());
    } catch (IOException e) {
      throw connectionFailure(e, resource);
    }
    if (response.statusCode() == RedirectFollowingFetcher.TOO_MANY_REQUESTS) {
      throw rateLimited(resource);
    }
    return new Response(response.statusCode(), body);
  }

  /**
   * Downloads {@code url} into a temporary file bounded by {@code maxBytes}. Off-origin redirects
   * (Cloud serves attachment bytes from its media service) are followed with {@code Authorization}
   * dropped - the redirect target is a pre-signed address that needs no credentials, and the token
   * must never travel to a host the library owner did not configure.
   */
  BoundedDownloader.DownloadedFile download(String url, String fileName, long maxBytes)
      throws ConfluenceAccessException, InterruptedException {
    String resource = "der Anhang " + fileName;
    // a download is a call to the instance like any other - it counts against the budget
    chargeBudget();
    meter.recordRequest();
    try {
      return downloader.downloadBounded(
          httpClient,
          url,
          fileName,
          maxBytes,
          authorizationHeader(),
          RedirectFollowingFetcher.RedirectPolicy.DROP_AUTHORIZATION_OFF_ORIGIN,
          budgetedRetries);
    } catch (BoundedDownloader.AttachmentTooLargeException e) {
      throw e;
    } catch (ConfluenceAccessException e) {
      throw e;
    } catch (BoundedDownloader.HttpStatusException e) {
      if (e.statusCode() == RedirectFollowingFetcher.TOO_MANY_REQUESTS) {
        throw rateLimited(resource);
      }
      throw failure(e.statusCode(), resource);
    } catch (IOException e) {
      throw connectionFailure(e, resource);
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

  private ConfluenceAccessException.RateLimited rateLimited(String resource) {
    return new ConfluenceAccessException.RateLimited(
        "Confluence begrenzt die Anfragerate (HTTP "
            + RedirectFollowingFetcher.TOO_MANY_REQUESTS
            + "); nach "
            + properties.maxRateLimitRetries()
            + " Wartezyklen für "
            + resource
            + " aufgegeben.");
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
