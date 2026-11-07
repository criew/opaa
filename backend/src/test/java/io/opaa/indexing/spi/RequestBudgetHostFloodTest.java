package io.opaa.indexing.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.security.ScriptedResolution;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.RateLimitPolicy;
import io.opaa.sourceaccess.RedirectFollowingFetcher;
import io.opaa.sourceaccess.SourceRequestPolicy;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A run whose source redirects every request to ever new host names, as a crawl following redirects
 * off its origin meets it (ADR-0063, Entscheidung 9). Every hop is checked before a connection and
 * pins its name for 60 seconds; the run's bound of hosts keeps it from filling the table of pinned
 * names, the fetches past the bound fail as refused targets.
 */
class RequestBudgetHostFloodTest {

  private static final String DOMAIN = "flood.opaa.test";
  private static final int MAX_HOSTS = 100;
  private static final int FETCHES = 300;

  private final AtomicInteger names = new AtomicInteger();
  private final HttpClient source = mock(HttpClient.class);
  private ScriptedResolution dns;

  @BeforeEach
  void setUp() throws Exception {
    dns = ScriptedResolution.install().answeringUnder(DOMAIN, "203.0.113.10");
    when(source.send(any(HttpRequest.class), any()))
        .thenAnswer(invocation -> redirectToAFreshName(invocation.getArgument(0)));
  }

  @AfterEach
  void tearDown() {
    dns.close();
  }

  /** Regression guard: a run pins at most its bound of names, however many it is sent to. */
  @Test
  void aRunFollowingRedirectsToEverNewNamesPinsAtMostItsBoundOfHosts() throws Exception {
    RequestBudget budget =
        RequestBudget.forRun(
            new SourceRequestPolicy("OPAA-Indexer/test", RateLimitPolicy.NONE, d -> {})
                .withMaxHostsPerRun(MAX_HOSTS));
    TargetAddressValidator validator = new TargetAddressValidator(true, List.of());
    int refused = 0;

    for (int i = 0; i < FETCHES; i++) {
      try {
        RedirectFollowingFetcher.sendFollowingRedirects(
            source,
            "http://" + freshName() + "/file" + i,
            Duration.ofSeconds(5),
            Map.of(),
            validator,
            RedirectFollowingFetcher.RedirectPolicy.DROP_AUTHORIZATION_OFF_ORIGIN,
            new SourceRequestPolicy("OPAA-Indexer/test", RateLimitPolicy.NONE, d -> {})
                .rateLimitHandling(budget));
      } catch (RequestBudget.HostLimitExceededException e) {
        refused++;
      }
    }

    assertThat(dns.namesAskedUnder(DOMAIN))
        .as("names this run had checked and pinned, out of %s it was sent to", names.get())
        .hasSizeLessThanOrEqualTo(MAX_HOSTS);
    assertThat(refused).as("fetches refused once the bound was reached").isPositive();
  }

  private String freshName() {
    return "n" + names.incrementAndGet() + "." + DOMAIN;
  }

  private HttpResponse<InputStream> redirectToAFreshName(HttpRequest request) {
    @SuppressWarnings("unchecked")
    HttpResponse<InputStream> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(302);
    when(response.headers())
        .thenReturn(
            HttpHeaders.of(
                Map.of("Location", List.of("http://" + freshName() + "/next")), (a, b) -> true));
    when(response.body()).thenReturn(new ByteArrayInputStream(new byte[0]));
    when(response.uri()).thenReturn(URI.create(request.uri().toString()));
    when(response.request()).thenReturn(request);
    return response;
  }
}
