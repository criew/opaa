package io.opaa.sourceaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage of {@link RedirectFollowingFetcher}: one case per {@link
 * RedirectFollowingFetcher.RedirectPolicy} branch (off-origin handling, protocol-downgrade refusal
 * under both policies), the {@link RedirectFollowingFetcher#MAX_REDIRECTS} hop limit, and the
 * post-hoc check the {@code REJECT_OFF_ORIGIN} policy alone applies against a caller-supplied
 * client that already auto-follows redirects on its own (see this class's own asymmetry note).
 *
 * <p>Target validation is exercised on its own dedicated stand ({@code TargetAddressValidatorTest})
 * - disabled here since every server this class talks to is deliberately loopback.
 */
class RedirectFollowingFetcherTest {

  private HttpServer origin;
  private HttpServer foreign;
  private String originUrl;
  private String foreignUrl;

  @BeforeEach
  void setUp() throws IOException {
    origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    origin.start();
    originUrl = "http://127.0.0.1:" + origin.getAddress().getPort();

    // "localhost", not a 127/8 alias like 127.0.0.2: macOS binds only 127.0.0.1 by default
    // , and every foreign-host check under test compares host STRINGS, so a different
    // literal on the same loopback carries the same meaning as a second address.
    foreign = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    foreign.start();
    foreignUrl = "http://localhost:" + foreign.getAddress().getPort();
  }

  @AfterEach
  void tearDown() {
    origin.stop(0);
    foreign.stop(0);
  }

  @Test
  void dropAuthorizationOffOrigin_keepsFollowingButDropsAuthorizationHeader()
      throws IOException, InterruptedException {
    AtomicReference<String> receivedAuthorization = new AtomicReference<>("(never contacted)");
    foreign.createContext(
        "/target",
        exchange -> {
          receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          respond(exchange, 200, "content");
        });
    origin.createContext("/start", exchange -> redirectTo(exchange, foreignUrl + "/target"));

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Authorization", "Basic dGVzdDp0ZXN0");
    HttpResponse<InputStream> response =
        RedirectFollowingFetcher.sendFollowingRedirects(
            productionClient(),
            originUrl + "/start",
            Duration.ofSeconds(5),
            headers,
            TargetAddressValidator.disabled(),
            RedirectFollowingFetcher.RedirectPolicy.DROP_AUTHORIZATION_OFF_ORIGIN);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(receivedAuthorization.get()).isNull();
  }

  @Test
  void rejectOffOrigin_throwsBeforeContactingTheForeignHost()
      throws IOException, InterruptedException {
    AtomicInteger foreignHits = new AtomicInteger(0);
    foreign.createContext(
        "/target",
        exchange -> {
          foreignHits.incrementAndGet();
          respond(exchange, 200, "content");
        });
    origin.createContext("/start", exchange -> redirectTo(exchange, foreignUrl + "/target"));

    Map<String, String> headers = new LinkedHashMap<>();
    assertThatThrownBy(
            () ->
                RedirectFollowingFetcher.sendFollowingRedirects(
                    productionClient(),
                    originUrl + "/start",
                    Duration.ofSeconds(5),
                    headers,
                    TargetAddressValidator.disabled(),
                    RedirectFollowingFetcher.RedirectPolicy.REJECT_OFF_ORIGIN))
        .isInstanceOf(RedirectFollowingFetcher.RedirectRejectedException.class)
        .satisfies(
            e ->
                assertThat(((RedirectFollowingFetcher.RedirectRejectedException) e).reason())
                    .isEqualTo(RedirectFollowingFetcher.RedirectRejectionReason.FOREIGN_HOST));
    assertThat(foreignHits.get()).isZero();
  }

  @Test
  void protocolDowngrade_refusedUnderDropAuthorizationPolicyAsPlainIOException()
      throws IOException, InterruptedException {
    origin.createContext(
        "/start", exchange -> redirectTo(exchange, "http://127.0.0.1:1/downgraded"));

    // Mocked at the HttpClient level (like BoundedDownloaderTest's identical downgrade test) -
    // no real https listener is needed since only the response's own declared uri()/Location are
    // ever compared, never an actual TLS connection to the downgrade target.
    HttpResponse<InputStream> response = mockRedirectResponse("https://example.com/start", 302);
    HttpClient httpClient = mock(HttpClient.class);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);

    Map<String, String> headers = new LinkedHashMap<>();
    assertThatThrownBy(
            () ->
                RedirectFollowingFetcher.sendFollowingRedirects(
                    httpClient,
                    "https://example.com/start",
                    Duration.ofSeconds(5),
                    headers,
                    TargetAddressValidator.disabled(),
                    RedirectFollowingFetcher.RedirectPolicy.DROP_AUTHORIZATION_OFF_ORIGIN))
        .isInstanceOf(IOException.class)
        .isNotInstanceOf(RedirectFollowingFetcher.RedirectRejectedException.class)
        .hasMessageContaining("protocol downgrade");
  }

  @Test
  void protocolDowngrade_refusedUnderRejectOffOriginPolicyAsRedirectRejectedException()
      throws IOException, InterruptedException {
    HttpResponse<InputStream> response = mockRedirectResponse("https://example.com/start", 302);
    HttpClient httpClient = mock(HttpClient.class);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);

    Map<String, String> headers = new LinkedHashMap<>();
    assertThatThrownBy(
            () ->
                RedirectFollowingFetcher.sendFollowingRedirects(
                    httpClient,
                    "https://example.com/start",
                    Duration.ofSeconds(5),
                    headers,
                    TargetAddressValidator.disabled(),
                    RedirectFollowingFetcher.RedirectPolicy.REJECT_OFF_ORIGIN))
        .isInstanceOf(RedirectFollowingFetcher.RedirectRejectedException.class)
        .satisfies(
            e ->
                assertThat(((RedirectFollowingFetcher.RedirectRejectedException) e).reason())
                    .isEqualTo(
                        RedirectFollowingFetcher.RedirectRejectionReason.PROTOCOL_DOWNGRADE));
  }

  /**
   * A mocked 302 response whose {@code uri()} equals {@code requestUri} (so the REJECT_OFF_ORIGIN
   * post-hoc check does not fire before the downgrade check is even reached) and whose {@code
   * Location} points at the plain-http downgrade target.
   */
  private static HttpResponse<InputStream> mockRedirectResponse(String requestUri, int status) {
    @SuppressWarnings("unchecked")
    HttpResponse<InputStream> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.uri()).thenReturn(URI.create(requestUri));
    when(response.headers())
        .thenReturn(
            HttpHeaders.of(
                Map.of("Location", List.of("http://example.com/downgraded")), (a, b) -> true));
    when(response.body()).thenReturn(InputStream.nullInputStream());
    return response;
  }

  @Test
  void hopLimitExceeded_returnsTheRawRedirectResponseInsteadOfLoopingForever()
      throws IOException, InterruptedException {
    // Every hop redirects to the next, forming a chain far longer than MAX_REDIRECTS (5) - both
    // policies simply stop after MAX_REDIRECTS hops and hand back whatever 3xx response the loop
    // was on at that point, rather than throwing or looping past the bound.
    AtomicInteger requestCount = new AtomicInteger(0);
    origin.createContext(
        "/loop",
        exchange -> {
          requestCount.incrementAndGet();
          redirectTo(exchange, originUrl + "/loop?n=" + requestCount.get());
        });

    Map<String, String> headers = new LinkedHashMap<>();
    HttpResponse<InputStream> response =
        RedirectFollowingFetcher.sendFollowingRedirects(
            productionClient(),
            originUrl + "/loop",
            Duration.ofSeconds(5),
            headers,
            TargetAddressValidator.disabled(),
            RedirectFollowingFetcher.RedirectPolicy.DROP_AUTHORIZATION_OFF_ORIGIN);

    assertThat(response.statusCode()).isEqualTo(302);
    // hop starts at 0 and the loop condition is "hop >= MAX_REDIRECTS" checked *after* sending -
    // MAX_REDIRECTS + 1 requests are sent in total (hops 0..MAX_REDIRECTS inclusive) before the
    // (MAX_REDIRECTS + 1)-th response is returned unfollowed.
    assertThat(requestCount.get()).isEqualTo(RedirectFollowingFetcher.MAX_REDIRECTS + 1);
  }

  @Test
  void rejectOffOrigin_postHocChecksAnAlreadyFollowedResponseAgainstARedirectNormalClient()
      throws IOException, InterruptedException {
    // This post-hoc check only exists for REJECT_OFF_ORIGIN (see this class's own Javadoc) - a
    // caller-supplied HttpClient built with Redirect.NORMAL (not the production Redirect.NEVER)
    // auto-follows the redirect itself, so this loop's own hop-by-hop Location handling never
    // sees it; without the check, a foreign-host redirect would silently succeed.
    foreign.createContext("/target", exchange -> respond(exchange, 200, "content"));
    origin.createContext("/start", exchange -> redirectTo(exchange, foreignUrl + "/target"));

    HttpClient autoFollowingClient =
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    Map<String, String> headers = new LinkedHashMap<>();
    assertThatThrownBy(
            () ->
                RedirectFollowingFetcher.sendFollowingRedirects(
                    autoFollowingClient,
                    originUrl + "/start",
                    Duration.ofSeconds(5),
                    headers,
                    TargetAddressValidator.disabled(),
                    RedirectFollowingFetcher.RedirectPolicy.REJECT_OFF_ORIGIN))
        .isInstanceOf(RedirectFollowingFetcher.RedirectRejectedException.class)
        .satisfies(
            e ->
                assertThat(((RedirectFollowingFetcher.RedirectRejectedException) e).reason())
                    .isEqualTo(RedirectFollowingFetcher.RedirectRejectionReason.FOREIGN_HOST));
  }

  @Test
  void dropAuthorizationOffOrigin_isNotAffectedByAnAlreadyFollowedRedirectNormalClient()
      throws IOException, InterruptedException {
    // Mirrors the previous test but for the other policy: the post-hoc check is REJECT_OFF_ORIGIN
    // only (documented asymmetry in this class's own Javadoc) - an already-auto-followed
    // off-origin redirect under DROP_AUTHORIZATION_OFF_ORIGIN is simply accepted, not rejected.
    // Pinning the real, verified outcome rather than an assumed one: the JDK's own Redirect.NORMAL
    // implementation already strips Authorization once the redirect target's host differs, so the
    // foreign host below never receives it - this class does not depend on that JDK behaviour (see
    // its own Javadoc), it only happens to hold here too. Safe in production regardless, since
    // every client this package builds uses Redirect.NEVER and never auto-follows at all.
    AtomicReference<String> receivedAuthorization = new AtomicReference<>("(never contacted)");
    foreign.createContext(
        "/target",
        exchange -> {
          receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          respond(exchange, 200, "content");
        });
    origin.createContext("/start", exchange -> redirectTo(exchange, foreignUrl + "/target"));

    HttpClient autoFollowingClient =
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Authorization", "Basic dGVzdDp0ZXN0");
    HttpResponse<InputStream> response =
        RedirectFollowingFetcher.sendFollowingRedirects(
            autoFollowingClient,
            originUrl + "/start",
            Duration.ofSeconds(5),
            headers,
            TargetAddressValidator.disabled(),
            RedirectFollowingFetcher.RedirectPolicy.DROP_AUTHORIZATION_OFF_ORIGIN);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(receivedAuthorization.get()).isNull();
  }

  // --- 429 handling ----------------------------------------------------------------------------

  /** Records what the fetcher told it, so a test can assert on waits and retries. */
  private static final class RecordingListener implements RateLimitListener {
    final List<Duration> throttled = new ArrayList<>();
    int retries;

    @Override
    public void throttled(int statusCode, Duration wait) {
      throttled.add(wait);
    }

    @Override
    public void retrying() {
      retries++;
    }
  }

  private final List<Duration> sleeps = new ArrayList<>();

  private RateLimitHandling handling(RateLimitPolicy policy, RateLimitListener listener) {
    return new RateLimitHandling(policy, sleeps::add, listener);
  }

  /** Answers {@code 429} with {@code retryAfter} (none when {@code null}) for the first n hits. */
  private static void throttle(com.sun.net.httpserver.HttpExchange exchange, String retryAfter)
      throws IOException {
    if (retryAfter != null) {
      exchange.getResponseHeaders().set("Retry-After", retryAfter);
    }
    exchange.sendResponseHeaders(429, -1);
    exchange.close();
  }

  private HttpResponse<InputStream> fetch(String url, RateLimitHandling rateLimit)
      throws IOException, InterruptedException {
    return RedirectFollowingFetcher.sendFollowingRedirects(
        productionClient(),
        url,
        Duration.ofSeconds(5),
        Map.of(),
        TargetAddressValidator.disabled(),
        RedirectFollowingFetcher.RedirectPolicy.REJECT_OFF_ORIGIN,
        rateLimit);
  }

  @Test
  void rateLimit_waitsRetryAfterAndRetriesFromTheOriginalUrl()
      throws IOException, InterruptedException {
    AtomicInteger startHits = new AtomicInteger();
    AtomicInteger targetHits = new AtomicInteger();
    origin.createContext(
        "/start",
        exchange -> {
          startHits.incrementAndGet();
          redirectTo(exchange, originUrl + "/target");
        });
    origin.createContext(
        "/target",
        exchange -> {
          if (targetHits.getAndIncrement() == 0) {
            throttle(exchange, "3");
          } else {
            respond(exchange, 200, "content");
          }
        });
    RecordingListener listener = new RecordingListener();

    HttpResponse<InputStream> response =
        fetch(
            originUrl + "/start", handling(RateLimitPolicy.of(3, Duration.ofMinutes(2)), listener));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(new String(response.body().readAllBytes(), StandardCharsets.UTF_8))
        .isEqualTo("content");
    assertThat(sleeps).containsExactly(Duration.ofSeconds(3));
    assertThat(listener.throttled).containsExactly(Duration.ofSeconds(3));
    assertThat(listener.retries).isEqualTo(1);
    assertThat(startHits.get()).as("the retry starts over at the original URL").isEqualTo(2);
  }

  @Test
  void rateLimit_defaultsTheWaitWithoutAUsableHeaderAndCapsItAtMaxWait()
      throws IOException, InterruptedException {
    AtomicInteger hits = new AtomicInteger();
    origin.createContext(
        "/throttled",
        exchange -> {
          switch (hits.getAndIncrement()) {
            case 0 -> throttle(exchange, null);
            case 1 -> throttle(exchange, "bald");
            case 2 -> throttle(exchange, "600");
            default -> respond(exchange, 200, "content");
          }
        });

    HttpResponse<InputStream> response =
        fetch(
            originUrl + "/throttled",
            handling(
                new RateLimitPolicy(3, Duration.ofSeconds(5), Duration.ofSeconds(20)),
                RateLimitListener.NONE));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(sleeps)
        .containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(20));
  }

  @Test
  void rateLimit_returnsTheLastTooManyRequestsOnceRetriesAreSpent()
      throws IOException, InterruptedException {
    origin.createContext("/throttled", exchange -> throttle(exchange, "1"));
    RecordingListener listener = new RecordingListener();

    HttpResponse<InputStream> response =
        fetch(
            originUrl + "/throttled",
            handling(RateLimitPolicy.of(2, Duration.ofMinutes(2)), listener));

    assertThat(response.statusCode()).isEqualTo(429);
    assertThat(sleeps).hasSize(2);
    assertThat(listener.throttled).hasSize(2);
    assertThat(listener.retries).isEqualTo(2);
  }

  @Test
  void rateLimit_noneReturnsTheFirstTooManyRequestsWithoutWaiting()
      throws IOException, InterruptedException {
    AtomicInteger hits = new AtomicInteger();
    origin.createContext(
        "/throttled",
        exchange -> {
          hits.incrementAndGet();
          throttle(exchange, "1");
        });

    HttpResponse<InputStream> response =
        RedirectFollowingFetcher.sendFollowingRedirects(
            productionClient(),
            originUrl + "/throttled",
            Duration.ofSeconds(5),
            Map.of(),
            TargetAddressValidator.disabled(),
            RedirectFollowingFetcher.RedirectPolicy.REJECT_OFF_ORIGIN);

    assertThat(response.statusCode()).isEqualTo(429);
    assertThat(hits.get()).isEqualTo(1);
    assertThat(sleeps).isEmpty();
  }

  @Test
  void rateLimit_aListenerAbortingTheRetryEndsTheFetchWithItsException() {
    AtomicInteger hits = new AtomicInteger();
    origin.createContext(
        "/throttled",
        exchange -> {
          hits.incrementAndGet();
          throttle(exchange, "1");
        });
    RateLimitListener budgetExhausted =
        new RateLimitListener() {
          @Override
          public void retrying() throws IOException {
            throw new IOException("budget spent");
          }
        };

    assertThatThrownBy(
            () ->
                fetch(
                    originUrl + "/throttled",
                    handling(RateLimitPolicy.of(3, Duration.ofMinutes(2)), budgetExhausted)))
        .isInstanceOf(IOException.class)
        .hasMessage("budget spent");
    assertThat(hits.get()).as("no retry was sent").isEqualTo(1);
    assertThat(sleeps).hasSize(1);
  }

  private static HttpClient productionClient() {
    return SourceHttpClientFactory.buildHttpClient(null, -1, false);
  }

  private static void redirectTo(com.sun.net.httpserver.HttpExchange exchange, String location)
      throws IOException {
    exchange.getResponseHeaders().set("Location", location);
    exchange.sendResponseHeaders(302, -1);
    exchange.close();
  }

  private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
