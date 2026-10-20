package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HealthProbeTest {

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void anAddressAnswering200IsHealthy() throws IOException {
    String address = serve(200);

    assertThat(HealthProbe.healthy(address)).isTrue();
  }

  @Test
  void anAddressAnsweringOutOfServiceIsNotHealthy() throws IOException {
    String address = serve(503);

    assertThat(HealthProbe.healthy(address)).isFalse();
  }

  @Test
  void anAddressNobodyListensOnIsNotHealthy() throws IOException {
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }

    assertThat(HealthProbe.healthy("http://127.0.0.1:" + port + "/actuator/health/readiness"))
        .isFalse();
  }

  @Test
  void aMalformedAddressIsNotHealthy() {
    assertThat(HealthProbe.healthy("not an address")).isFalse();
  }

  private String serve(int status) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/actuator/health/readiness",
        exchange -> {
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/actuator/health/readiness";
  }
}
