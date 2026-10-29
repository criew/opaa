package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.sun.net.httpserver.HttpServer;
import io.opaa.security.ScriptedResolution;
import io.opaa.security.TargetAddressValidator;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DNS rebinding against the fetch of a provider's JWK set (ADR-0063): the provider host answers the
 * sign-in address check with a public address and the fetch with loopback, where an internal
 * service listens. The fetch must reach only the vetted address.
 */
class OidcDiscoveryClientRebindingTest {

  private static final String HOST = "idp.rebind.opaa.test";

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
    dns = ScriptedResolution.install().rebinding(HOST, "2001:db8::8443", "127.0.0.1");
  }

  @AfterEach
  void tearDown() {
    dns.close();
    internal.stop(0);
  }

  @Test
  void theJwkSetFetchReachesOnlyTheAddressTheCheckVetted() {
    OidcDiscoveryClient client =
        new OidcDiscoveryClient(new OidcAddressPolicy(new TargetAddressValidator(true, List.of())));

    OidcDiscoveryClient.OidcProbeException failure =
        catchThrowableOfType(
            OidcDiscoveryClient.OidcProbeException.class,
            () ->
                client.fetchJwkSet(
                    "http://" + HOST + ":" + internal.getAddress().getPort() + "/jwks"));

    assertThat(requests.get())
        .as("requests the internal service on 127.0.0.1 received after the check")
        .isZero();
    assertThat(failure).as("the fetch from the vetted address fails").isNotNull();
  }
}
