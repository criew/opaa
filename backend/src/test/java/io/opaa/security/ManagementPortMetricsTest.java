package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Where the metric endpoints open without a sign-in: on a management port of their own and on no
 * other, whatever the request claims about its host.
 */
class ManagementPortMetricsTest {

  @ParameterizedTest
  @ValueSource(
      strings = {"/actuator/prometheus", "/actuator/metrics", "/actuator/metrics/jvm.memory.used"})
  void aMetricEndpointIsOpenOnTheManagementPortOnly(String path) {
    RequestMatcher open =
        ManagementPortMetrics.onManagementPort(
            new MockEnvironment()
                .withProperty("server.port", "8080")
                .withProperty("management.server.port", "8081"));

    assertThat(open.matches(request(path, 8081))).isTrue();
    assertThat(open.matches(request(path, 8080))).isFalse();
    assertThat(ManagementPortMetrics.anyPort().matches(request(path, 8080))).isTrue();
  }

  @Test
  void aHostHeaderNamingTheManagementPortOpensNothing() {
    RequestMatcher open =
        ManagementPortMetrics.onManagementPort(
            new MockEnvironment().withProperty("management.server.port", "8081"));
    MockHttpServletRequest request = request("/actuator/prometheus", 8080);
    request.setServerPort(8081);
    request.addHeader("Host", "backend:8081");
    request.addHeader("X-Forwarded-Port", "8081");

    assertThat(open.matches(request)).isFalse();
  }

  @Test
  void otherPathsOnTheManagementPortAreNotMetrics() {
    RequestMatcher open =
        ManagementPortMetrics.onManagementPort(
            new MockEnvironment().withProperty("management.server.port", "8081"));

    assertThat(open.matches(request("/actuator/health/vector-store", 8081))).isFalse();
    assertThat(open.matches(request("/api/v1/query", 8081))).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "0", "-1", "8080"})
  void withoutAManagementPortOfTheirOwnTheyAreOpenNowhere(String managementPort) {
    MockEnvironment environment = new MockEnvironment().withProperty("server.port", "8080");
    if (!managementPort.isEmpty()) {
      environment.setProperty("management.server.port", managementPort);
    }
    RequestMatcher open = ManagementPortMetrics.onManagementPort(environment);

    for (int port : new int[] {8080, 8081, 0, -1}) {
      assertThat(open.matches(request("/actuator/prometheus", port))).isFalse();
    }
  }

  private static MockHttpServletRequest request(String path, int localPort) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setLocalPort(localPort);
    return request;
  }
}
