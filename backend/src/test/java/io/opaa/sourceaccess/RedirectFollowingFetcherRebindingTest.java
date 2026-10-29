package io.opaa.sourceaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.sun.net.httpserver.HttpServer;
import io.opaa.security.ScriptedResolution;
import io.opaa.security.TargetAddressValidator;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DNS rebinding against a source fetch (ADR-0063): the name of a source answers the target address
 * check with a public address and the connection with loopback, where an internal service listens.
 * The fetch must reach only the vetted address.
 */
class RedirectFollowingFetcherRebindingTest {

  private static final String HOST = "files.rebind.opaa.test";

  private HttpServer internal;
  private final AtomicInteger requests = new AtomicInteger();
  private ScriptedResolution dns;

  @BeforeEach
  void setUp() throws IOException {
    internal = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    internal.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    internal.start();
    dns = ScriptedResolution.install().rebinding(HOST, "2001:db8::80", "127.0.0.1");
  }

  @AfterEach
  void tearDown() {
    dns.close();
    internal.stop(0);
  }

  @Test
  void aSourceFetchReachesOnlyTheAddressTheCheckVetted() {
    String url = "http://" + HOST + ":" + internal.getAddress().getPort() + "/dokument.pdf";

    IOException failure =
        catchThrowableOfType(
            IOException.class,
            () ->
                RedirectFollowingFetcher.sendFollowingRedirects(
                    SourceHttpClientFactory.buildHttpClient(),
                    url,
                    Duration.ofSeconds(5),
                    Map.of(),
                    new TargetAddressValidator(true, List.of()),
                    RedirectFollowingFetcher.RedirectPolicy.REJECT_OFF_ORIGIN));

    assertThat(requests.get())
        .as("requests the internal service on 127.0.0.1 received after the check")
        .isZero();
    assertThat(failure).as("the fetch from the vetted address 2001:db8::80 fails").isNotNull();
  }
}
