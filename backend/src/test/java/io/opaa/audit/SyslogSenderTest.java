package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Octet-counted syslog over plain TCP (RFC 6587) against a local receiver. */
class SyslogSenderTest {

  private ServerSocket server;

  @BeforeEach
  void setUp() throws IOException {
    server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
  }

  @AfterEach
  void tearDown() throws IOException {
    server.close();
  }

  @Test
  void everyMessageIsFramedByItsOctetCount() throws Exception {
    CompletableFuture<String> received =
        CompletableFuture.supplyAsync(
            () -> {
              try (Socket socket = server.accept();
                  InputStream in = socket.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
              } catch (IOException e) {
                throw new IllegalStateException(e);
              }
            });

    new SyslogSender("127.0.0.1", server.getLocalPort(), false, Duration.ofSeconds(5))
        .send(List.of("<85>1 erste", "<85>1 zweite Nachricht mit ä"));

    assertThat(received.get(5, TimeUnit.SECONDS))
        .isEqualTo("11 <85>1 erste29 <85>1 zweite Nachricht mit ä");
  }

  @Test
  void anUnreachableReceiverFailsTheDelivery() throws IOException {
    int closedPort = server.getLocalPort();
    server.close();

    assertThatThrownBy(
            () ->
                new SyslogSender("127.0.0.1", closedPort, false, Duration.ofSeconds(2))
                    .send(List.of("<85>1 x")))
        .isInstanceOf(IOException.class);
  }
}
