package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

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
    new TargetAddressValidator(true, List.of()).validateHost(host, internal.getLocalPort());

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
    new TargetAddressValidator(true, List.of()).validate(uri);
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
