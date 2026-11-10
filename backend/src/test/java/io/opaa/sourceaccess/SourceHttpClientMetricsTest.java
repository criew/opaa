package io.opaa.sourceaccess;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.security.TargetAddressValidator;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Every attempt of a source fetch counts as target {@code connector} with the connector type as
 * {@code kind} on the global registry Spring Boot binds the application's registry to: a {@code
 * 429} that is waited out and the successful retry are two requests.
 */
class SourceHttpClientMetricsTest {

  @Test
  void everyAttemptOfAThrottledFetchCountsWithItsConnectorType() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/feed",
        exchange -> {
          int status = calls.incrementAndGet() == 1 ? 429 : 200;
          if (status == 429) {
            exchange.getResponseHeaders().add("Retry-After", "0");
          }
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    server.start();
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    Metrics.globalRegistry.add(meterRegistry);
    try (HttpClient client = SourceHttpClientFactory.buildHttpClient("rss_feed")) {
      HttpResponse<InputStream> response =
          RedirectFollowingFetcher.sendFollowingRedirects(
              client,
              "http://127.0.0.1:" + server.getAddress().getPort() + "/feed",
              Duration.ofSeconds(5),
              Map.of(),
              new TargetAddressValidator(true, java.util.List.of("127.0.0.1")),
              RedirectFollowingFetcher.RedirectPolicy.REJECT_OFF_ORIGIN,
              new RateLimitHandling(
                  new RateLimitPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(1)),
                  wait -> {},
                  RateLimitListener.NONE));
      response.body().close();
      assertThat(response.statusCode()).isEqualTo(200);
    } finally {
      Metrics.globalRegistry.remove(meterRegistry);
      server.stop(0);
    }

    assertThat(requests(meterRegistry, "throttled")).isEqualTo(1);
    assertThat(requests(meterRegistry, "success")).isEqualTo(1);
  }

  private static long requests(SimpleMeterRegistry meterRegistry, String outcome) {
    return meterRegistry
        .get("opaa.outbound.requests")
        .tag("target", "connector")
        .tag("kind", "rss_feed")
        .tag("outcome", outcome)
        .tag("path_class", "background")
        .timer()
        .count();
  }
}
