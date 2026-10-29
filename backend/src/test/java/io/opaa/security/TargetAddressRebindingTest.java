package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.opaa.security.TargetAddressValidator.Scope;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DNS rebinding against the target address check (ADR-0063): the name server answers the check with
 * a public address and every later lookup with loopback, where an internal service listens. A
 * client connecting right after the check must not reach it - shown for a plain socket client (how
 * JavaMail and SMB connect) and for OkHttp (the model clients of Spring AI).
 */
class TargetAddressRebindingTest {

  private static final String PUBLIC = "2001:db8::25";
  private static final String LOOPBACK = "127.0.0.1";

  private ServerSocket internal;
  private ScriptedResolution dns;

  @BeforeEach
  void setUp() throws IOException {
    internal = new ServerSocket(0, 50, InetAddress.getByName(LOOPBACK));
    dns = ScriptedResolution.install();
  }

  @AfterEach
  void tearDown() throws IOException {
    dns.close();
    internal.close();
  }

  @Test
  void aSocketClientConnectsOnlyToTheAddressTheCheckVetted() throws IOException {
    String host = "smtp.rebind.opaa.test";
    dns.rebinding(host, PUBLIC, LOOPBACK);
    new TargetAddressValidator(true, List.of())
        .validateHost(host, internal.getLocalPort(), Scope.PER_CONNECTION);

    IOException failure =
        catchThrowableOfType(
            IOException.class,
            () -> {
              try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, internal.getLocalPort()), 2000);
              }
            });

    assertThat(reachedInternalService())
        .as("a connection reached the internal service on %s after the check", LOOPBACK)
        .isFalse();
    assertThat(failure).as("the connection to the vetted address %s fails", PUBLIC).isNotNull();
  }

  @Test
  void anOkHttpClientConnectsOnlyToTheAddressTheCheckVetted() throws IOException {
    String host = "model.rebind.opaa.test";
    dns.rebinding(host, PUBLIC, LOOPBACK);
    URI uri = URI.create("http://" + host + ":" + internal.getLocalPort() + "/v1/models");
    new TargetAddressValidator(true, List.of()).validate(uri, Scope.PER_CONNECTION);
    OkHttpClient client =
        new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(2))
            .readTimeout(Duration.ofSeconds(2))
            .build();

    IOException failure =
        catchThrowableOfType(
            IOException.class,
            () -> {
              try (Response ignored =
                  client.newCall(new Request.Builder().url(uri.toString()).build()).execute()) {
                // a response at all means the internal service was reached
              }
            });

    assertThat(reachedInternalService())
        .as("a connection reached the internal service on %s after the check", LOOPBACK)
        .isFalse();
    assertThat(failure).as("the connection to the vetted address %s fails", PUBLIC).isNotNull();
  }

  /** Regression guard: a check after the reuse window resolves afresh and moves the pin along. */
  @Test
  void aRepeatedCheckFollowsALegitimateAddressChange() throws Exception {
    String host = "moved.rebind.opaa.test";
    dns.rebinding(host, "2001:db8::1", "2001:db8::2");
    TargetAddressValidator validator = new TargetAddressValidator(true, List.of());

    validator.validateHost(host, 443, Scope.PER_CONNECTION);
    Thread.sleep(ResolutionPins.ANSWER_REUSE.plusMillis(100).toMillis());
    validator.validateHost(host, 443, Scope.PER_CONNECTION);

    assertThat(InetAddress.getAllByName(host))
        .extracting(InetAddress::getHostAddress)
        .containsExactly(InetAddress.getByName("2001:db8::2").getHostAddress());
  }

  /** Regression guard: checks before a connection - redirect hops, feed links - guard nothing. */
  @Test
  void aFloodOfConnectionChecksTakesNoPlaceInTheGuardTable() throws IOException {
    TargetAddressValidator validator = new TargetAddressValidator(true, List.of());
    int guardedBefore = ResolutionPins.process().guardedNames();

    for (int i = 0; i < 200; i++) {
      String host = "hop" + i + ".flood.rebind.opaa.test";
      dns.rebinding(host, "2001:db8::" + Integer.toHexString(i + 1), "127.0.0.1");
      validator.validateHost(host, 443, Scope.PER_CONNECTION);
    }

    assertThat(ResolutionPins.process().guardedNames()).isEqualTo(guardedBefore);
  }

  /** Through the outbound proxy the proxy resolves; nothing is pinned or guarded locally. */
  @Test
  void aCheckThroughTheOutboundProxyPinsAndGuardsNothing() throws IOException {
    String host = "proxied.rebind.opaa.test";
    dns.rebinding(host, "2001:db8::9", "2001:db8::10");
    int guardedBefore = ResolutionPins.process().guardedNames();

    new TargetAddressValidator(true, List.of()).validateHost(host, 443, true, Scope.STORED_TARGET);

    assertThat(ResolutionPins.process().guardedNames()).isEqualTo(guardedBefore);
    assertThat(InetAddress.getAllByName(host))
        .extracting(InetAddress::getHostAddress)
        .containsExactly(InetAddress.getByName("2001:db8::10").getHostAddress());
  }

  /** Whether a connection is waiting in the internal service's backlog. */
  private boolean reachedInternalService() throws IOException {
    internal.setSoTimeout(300);
    try (Socket accepted = internal.accept()) {
      return accepted != null;
    } catch (SocketTimeoutException none) {
      return false;
    }
  }
}
