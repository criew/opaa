package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.web.server.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Where the metric endpoints open without a sign-in: on the port the management web server bound
 * and on no other, whatever the request claims about its host.
 */
class ManagementPortMetricsTest {

  @ParameterizedTest
  @ValueSource(
      strings = {"/actuator/prometheus", "/actuator/metrics", "/actuator/metrics/jvm.memory.used"})
  void aMetricEndpointIsOpenOnTheManagementPortOnly(String path) {
    RequestMatcher open = open(started(null, 8080), started("management", 8081));

    assertThat(open.matches(request(path, 8081))).isTrue();
    assertThat(open.matches(request(path, 8080))).isFalse();
    assertThat(ManagementPortMetrics.anyPort().matches(request(path, 8080))).isTrue();
  }

  @Test
  void aRandomManagementPortIsTheOneTheServerBound() {
    RequestMatcher open = open(started(null, 41234), started("management", 41235));

    assertThat(open.matches(request("/actuator/prometheus", 41235))).isTrue();
    assertThat(open.matches(request("/actuator/prometheus", 41234))).isFalse();
  }

  @Test
  void aHostHeaderNamingTheManagementPortOpensNothing() {
    RequestMatcher open = open(started("management", 8081));
    MockHttpServletRequest request = request("/actuator/prometheus", 8080);
    request.setServerPort(8081);
    request.addHeader("Host", "backend:8081");
    request.addHeader("X-Forwarded-Port", "8081");

    assertThat(open.matches(request)).isFalse();
  }

  @Test
  void otherPathsOnTheManagementPortAreNotMetrics() {
    RequestMatcher open = open(started("management", 8081));

    assertThat(open.matches(request("/actuator/health/vector-store", 8081))).isFalse();
    assertThat(open.matches(request("/actuator", 8081))).isFalse();
    assertThat(open.matches(request("/api/v1/query", 8081))).isFalse();
  }

  @Test
  void withoutAManagementPortOfTheirOwnTheyAreOpenNowhere() {
    RequestMatcher open = open(started(null, 8080));

    for (int port : new int[] {8080, 8081, 0, -1}) {
      assertThat(open.matches(request("/actuator/prometheus", port))).isFalse();
    }
  }

  @Test
  void aRequestOutsideTheMetricPathsDoesNotLookUpThePort() {
    @SuppressWarnings("unchecked")
    ObjectProvider<ManagementPortMetrics> provider = mock(ObjectProvider.class);

    boolean matched =
        ManagementPortMetrics.onManagementPort(provider).matches(request("/api/v1/query", 8081));

    assertThat(matched).isFalse();
    verifyNoInteractions(provider);
  }

  @Test
  void withoutTheBeanTheyAreOpenNowhere() {
    @SuppressWarnings("unchecked")
    ObjectProvider<ManagementPortMetrics> none = mock(ObjectProvider.class);

    RequestMatcher open = ManagementPortMetrics.onManagementPort(none);

    assertThat(open.matches(request("/actuator/prometheus", 8081))).isFalse();
  }

  private static RequestMatcher open(ServletWebServerInitializedEvent... events) {
    ManagementPortMetrics metrics = new ManagementPortMetrics();
    for (ServletWebServerInitializedEvent event : events) {
      metrics.onApplicationEvent(event);
    }
    @SuppressWarnings("unchecked")
    ObjectProvider<ManagementPortMetrics> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(metrics);
    return ManagementPortMetrics.onManagementPort(provider);
  }

  private static ServletWebServerInitializedEvent started(String namespace, int port) {
    WebServer server = mock(WebServer.class);
    when(server.getPort()).thenReturn(port);
    ServletWebServerApplicationContext context = mock(ServletWebServerApplicationContext.class);
    when(context.getServerNamespace()).thenReturn(namespace);
    return new ServletWebServerInitializedEvent(server, context);
  }

  private static MockHttpServletRequest request(String path, int localPort) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setLocalPort(localPort);
    return request;
  }
}
