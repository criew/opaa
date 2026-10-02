package io.opaa.sourceaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.opaa.security.RebindingHostLookup;
import io.opaa.security.TargetAddressValidator;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for #1860: the client every HTTP source connector fetches with (Confluence, RSS,
 * HTTP directory, original access) connects only to an address that passed the target check at
 * connect time. {@code localhost} answers a public address to the check and the loopback to the
 * connection; the fetch must be refused before it reaches the local server listening there.
 */
class SourceDnsRebindingTest {

  private final AtomicInteger requests = new AtomicInteger();
  private HttpServer server;
  private String url;

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          byte[] body = "<rss/>".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    url = "http://localhost:" + server.getAddress().getPort() + "/feed.xml";
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void aSourceThatRebindsAfterTheCheckIsRefusedWithoutAnyRequest() {
    RebindingHostLookup lookup = new RebindingHostLookup("localhost");
    TargetAddressValidator validator = new TargetAddressValidator(true, List.of(), lookup);
    HttpClient client = SourceHttpClientFactory.buildHttpClient(validator, null, -1, false);

    assertThatThrownBy(
            () ->
                RedirectFollowingFetcher.sendFollowingRedirects(
                    client,
                    url,
                    Duration.ofSeconds(5),
                    Map.of(),
                    validator,
                    RedirectFollowingFetcher.RedirectPolicy.REJECT_OFF_ORIGIN))
        .isInstanceOf(TargetAddressValidator.TargetAddressBlockedException.class)
        .hasMessageContaining("gesperrten Adressbereich");

    assertThat(requests).hasValue(0);
    assertThat(lookup.lookups()).isEqualTo(2);
  }

  @Test
  void anAllowlistedSourceStillConnectsToItsInternalAddress() throws Exception {
    TargetAddressValidator validator = new TargetAddressValidator(true, List.of("localhost"));
    HttpClient client = SourceHttpClientFactory.buildHttpClient(validator, null, -1, false);

    HttpResponse<InputStream> response =
        RedirectFollowingFetcher.sendFollowingRedirects(
            client,
            url,
            Duration.ofSeconds(5),
            Map.of(),
            validator,
            RedirectFollowingFetcher.RedirectPolicy.REJECT_OFF_ORIGIN);

    try (InputStream body = response.body()) {
      assertThat(new String(body.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("<rss/>");
    }
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(requests).hasValue(1);
  }
}
