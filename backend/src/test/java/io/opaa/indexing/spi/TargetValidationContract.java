package io.opaa.indexing.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.opaa.knowledge.SourceType;
import io.opaa.security.ScriptedResolution;
import io.opaa.security.TargetAddressValidator;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What every connector with an outbound connection keeps in its connection test: an address in a
 * blocked range, a name resolving to one and a redirect leading to one are refused before a
 * connection reaches it, with the German refusal of {@link TargetAddressValidator}. A subclass runs
 * its real connector with every request aimed at the target it is handed, on the address a person
 * configures or, for a connector whose address is fixed, on its API base; {@code
 * TargetValidationContractCoverageTest} holds every remote connector to have one.
 */
@Timeout(60)
public abstract class TargetValidationContract {

  /** The words every refusal of {@link TargetAddressValidator} carries. */
  public static final String REFUSAL = "gesperrten Adressbereich";

  private Listener internal;

  /** The connector this contract runs. */
  protected abstract SourceType type();

  /**
   * Runs the connection test of the connector, every request of it aimed at {@code target} - an
   * address {@code http://host:port} without a path - and checked by {@code validator}.
   */
  protected abstract SourceConnectionTestResult testConnection(
      URI target, TargetAddressValidator validator) throws Exception;

  /** Whether the connector speaks HTTP, so that a redirect could lead it somewhere else. */
  protected boolean speaksHttp() {
    return true;
  }

  @BeforeEach
  void listenOnLoopback() {
    internal = Listener.closing();
  }

  @AfterEach
  void stopListening() {
    internal.close();
  }

  /** The connection test reaches its target when the check admits it, so a refusal means it. */
  @Test
  void theConnectionTestReachesATargetTheCheckAdmits() throws Exception {
    testConnection(internal.address(), admitting(internal));

    assertThat(internal.connections())
        .as("connections the admitted target received from the %s connection test", type())
        .isPositive();
  }

  // regression guard for #267: a target in a blocked range is refused before any connection
  @ParameterizedTest(name = "{0}")
  @MethodSource("blockedHosts")
  void aTargetInABlockedRangeIsRefusedBeforeAnyConnection(String range, String host)
      throws Exception {
    SourceConnectionTestResult result =
        testConnection(URI.create("http://" + host + ":" + internal.port()), strict());

    assertRefusedWithoutConnection(result);
  }

  // regression guard for #267: a name is refused for the internal address it resolves to
  @Test
  void aNameResolvingToAnInternalAddressIsRefusedBeforeAnyConnection() throws Exception {
    String name = type().key().toLowerCase(Locale.ROOT).replace('_', '-') + ".intern.opaa.test";
    SourceConnectionTestResult result;
    try (ScriptedResolution dns =
        ScriptedResolution.install().rebinding(name, "127.0.0.1", "127.0.0.1")) {
      result = testConnection(URI.create("http://" + name + ":" + internal.port()), strict());
      assertThat(dns.lookups(name)).as("lookups of the scripted name").isPositive();
    }

    assertRefusedWithoutConnection(result);
  }

  // regression guard for #267: a redirect is checked like the address it leads to
  @Test
  void aRedirectToAnInternalTargetIsNotFollowed() throws Exception {
    assumeTrue(speaksHttp(), "a connector without HTTP follows no redirect");
    SourceConnectionTestResult result;
    try (Listener front = Listener.redirectingTo(internal.address() + "/intern")) {
      result = testConnection(front.address(), admitting(front));

      assertThat(front.connections())
          .as("connections the admitted address received from the %s connection test", type())
          .isPositive();
    }

    assertThat(result.reachable()).isFalse();
    assertThat(internal.connections())
        .as("connections the redirect target received from the %s connection test", type())
        .isZero();
  }

  /** One case per blocked range: its name and a host of it, as a URI writes it. */
  static Stream<Arguments> blockedHosts() {
    return Stream.of(
        Arguments.of("IPv4 loopback", "127.0.0.1"),
        Arguments.of("IPv4 loopback outside 127.0.0.1", "127.0.0.2"),
        Arguments.of("unspecified address", "0.0.0.0"),
        Arguments.of("link-local cloud metadata", "169.254.169.254"),
        Arguments.of("private 10/8", "10.0.0.1"),
        Arguments.of("private 172.16/12", "172.16.0.1"),
        Arguments.of("private 192.168/16", "192.168.0.1"),
        Arguments.of("carrier-grade NAT", "100.64.0.1"),
        Arguments.of("IPv6 loopback", "[::1]"),
        Arguments.of("IPv6 link-local", "[fe80::1]"),
        Arguments.of("IPv6 unique local", "[fd00::1]"),
        Arguments.of("IPv4-mapped loopback", "[::ffff:127.0.0.1]"),
        Arguments.of("IPv4-mapped cloud metadata", "[::ffff:169.254.169.254]"),
        Arguments.of("IPv4-compatible loopback", "[::127.0.0.1]"),
        Arguments.of("NAT64 well-known prefix", "[64:ff9b::a9fe:a9fe]"),
        // regression guard for #3360: prefixes embedding the cloud metadata address
        Arguments.of("NAT64 local-use prefix", "[64:ff9b:1::a9fe:a9fe]"),
        Arguments.of("6to4", "[2002:a9fe:a9fe::]"),
        Arguments.of("Teredo", "[2001::5601:5601]"),
        Arguments.of("IPv4-translated (SIIT)", "[::ffff:0:a9fe:a9fe]"),
        Arguments.of("discard-only prefix", "[100::1]"),
        Arguments.of("this network 0/8", "0.1.2.3"));
  }

  private void assertRefusedWithoutConnection(SourceConnectionTestResult result) {
    assertThat(result.reachable()).isFalse();
    assertThat(result.message()).contains(REFUSAL);
    assertThat(internal.connections())
        .as("connections the internal target received from the %s connection test", type())
        .isZero();
  }

  private static TargetAddressValidator strict() {
    return new TargetAddressValidator(true, List.of());
  }

  /** A check that admits {@code listener} on its port alone, as an operator's allowlist entry. */
  private static TargetAddressValidator admitting(Listener listener) {
    return new TargetAddressValidator(true, List.of("127.0.0.1:" + listener.port()));
  }

  /**
   * A TCP listener on loopback counting the connections it accepts: it closes each at once, or
   * answers any HTTP request with a redirect to a fixed address.
   */
  static final class Listener implements AutoCloseable {

    private final ServerSocket socket;
    private final String redirect;
    private final AtomicInteger connections = new AtomicInteger();

    private Listener(String redirect) {
      this.redirect = redirect;
      try {
        socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      Thread acceptor = new Thread(this::accept, "target-validation-listener");
      acceptor.setDaemon(true);
      acceptor.start();
    }

    static Listener closing() {
      return new Listener(null);
    }

    static Listener redirectingTo(String location) {
      return new Listener(location);
    }

    int port() {
      return socket.getLocalPort();
    }

    URI address() {
      return URI.create("http://127.0.0.1:" + port());
    }

    int connections() {
      return connections.get();
    }

    private void accept() {
      while (!socket.isClosed()) {
        try (Socket connection = socket.accept()) {
          connections.incrementAndGet();
          if (redirect != null) {
            connection.setSoTimeout(2_000);
            skipRequestHead(connection.getInputStream());
            OutputStream out = connection.getOutputStream();
            out.write(
                ("HTTP/1.1 302 Found\r\nLocation: "
                        + redirect
                        + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
          }
        } catch (IOException e) {
          // a closed listener ends the loop; a client gone mid-request is just counted
        }
      }
    }

    private static void skipRequestHead(InputStream in) throws IOException {
      int matched = 0;
      byte[] end = {'\r', '\n', '\r', '\n'};
      for (int b = in.read(); b >= 0 && matched < end.length; b = in.read()) {
        matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
      }
    }

    @Override
    public void close() {
      try {
        socket.close();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
