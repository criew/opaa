package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.OpaaManagementPortTest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The metric endpoints on a management port of their own, as the Helm chart runs the backend: open
 * there without a sign-in for the scraper, absent from the API port. Real servers, real ports - a
 * Host header naming the management port opens nothing on the API port.
 */
@OpaaManagementPortTest
class ManagementPortMetricsIntegrationTest {

  @LocalServerPort private int apiPort;
  @LocalManagementPort private int managementPort;

  private final HttpClient http = HttpClient.newHttpClient();

  @AfterEach
  void closeClient() {
    http.close();
  }

  @Test
  void theScraperReadsTheMetricsOnTheManagementPortWithoutASignIn() throws Exception {
    assertThat(managementPort).isNotEqualTo(apiPort);

    HttpResponse<String> response = get(managementPort, "/actuator/prometheus");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("jvm_memory_used_bytes");
    assertThat(get(managementPort, "/actuator/metrics/jvm.memory.used").statusCode())
        .isEqualTo(200);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"/actuator/prometheus", "/actuator/metrics", "/actuator/metrics/jvm.memory.used"})
  void theApiPortServesNoMetrics(String path) throws Exception {
    assertThat(get(apiPort, path).statusCode()).isIn(401, 404);
  }

  @Test
  void theProbesStayOpenAndEverythingElseOfTheActuatorNeedsASignIn() throws Exception {
    assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);
    assertThat(get(managementPort, "/actuator/info").statusCode()).isEqualTo(200);
    assertThat(get(managementPort, "/actuator").statusCode()).isEqualTo(401);
    assertThat(get(managementPort, "/actuator/health/vector-store").statusCode()).isEqualTo(401);
  }

  private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
