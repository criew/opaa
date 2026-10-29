package io.opaa.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The metric endpoints of the actuator and where they open without a sign-in: only on a management
 * port of their own ({@code management.server.port}, set apart from {@code server.port}), which a
 * network policy can open to the scraper alone. On the API port they need a sign-in; without a port
 * of their own, everywhere. The port is the one the connection arrived on, which no header can
 * change.
 */
public final class ManagementPortMetrics {

  /** The paths of the metric endpoints. */
  public static final List<String> PATHS =
      List.of("/actuator/metrics", "/actuator/metrics/**", "/actuator/prometheus");

  private static final int DEFAULT_SERVER_PORT = 8080;

  private ManagementPortMetrics() {}

  /** Every request to a metric endpoint, whatever its port. */
  public static RequestMatcher anyPort() {
    return new OrRequestMatcher(
        PATHS.stream()
            .map(path -> (RequestMatcher) PathPatternRequestMatcher.withDefaults().matcher(path))
            .toList());
  }

  /** The requests to a metric endpoint that arrived on the management port of its own. */
  public static RequestMatcher onManagementPort(Environment environment) {
    int managementPort = ownManagementPort(environment);
    if (managementPort <= 0) {
      return request -> false;
    }
    return new AndRequestMatcher(
        (HttpServletRequest request) -> request.getLocalPort() == managementPort, anyPort());
  }

  /**
   * The configured management port, or {@code 0} when there is none of its own: unset, random
   * ({@code 0}), switched off ({@code -1}) or the same as the API port.
   */
  static int ownManagementPort(Environment environment) {
    Integer managementPort = environment.getProperty("management.server.port", Integer.class);
    if (managementPort == null || managementPort <= 0) {
      return 0;
    }
    int serverPort = environment.getProperty("server.port", Integer.class, DEFAULT_SERVER_PORT);
    return managementPort == serverPort ? 0 : managementPort;
  }
}
