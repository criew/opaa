package io.opaa.security;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

/**
 * The metric endpoints of the actuator and where they open without a sign-in: only on a management
 * port of their own ({@code management.server.port} apart from the API port), which a network
 * policy can open to the scraper alone. Without one they share the API port and need a sign-in. The
 * port is the one the management web server actually bound, a random one included, compared with
 * the port the connection arrived on, which no header can change.
 */
@Component
public class ManagementPortMetrics implements ApplicationListener<WebServerInitializedEvent> {

  /** The paths of the metric endpoints. */
  public static final List<String> PATHS =
      List.of("/actuator/metrics", "/actuator/metrics/**", "/actuator/prometheus");

  /** The server namespace Spring Boot gives the web server of a separate management port. */
  static final String MANAGEMENT_NAMESPACE = "management";

  private static final RequestMatcher METRIC_PATHS =
      new OrRequestMatcher(
          PATHS.stream()
              .map(path -> (RequestMatcher) PathPatternRequestMatcher.withDefaults().matcher(path))
              .toList());

  /** The bound management port, {@code 0} while there is none of its own. */
  private volatile int managementPort;

  @Override
  public void onApplicationEvent(WebServerInitializedEvent event) {
    if (MANAGEMENT_NAMESPACE.equals(event.getApplicationContext().getServerNamespace())) {
      managementPort = event.getWebServer().getPort();
    }
  }

  /** Whether {@code localPort} is the management port of its own. */
  boolean isManagementPort(int localPort) {
    int port = managementPort;
    return port > 0 && port == localPort;
  }

  /** Every request to a metric endpoint, whatever its port. */
  public static RequestMatcher anyPort() {
    return METRIC_PATHS;
  }

  /**
   * The requests to a metric endpoint that arrived on the management port of its own; none when the
   * context has no such bean (a web slice test).
   */
  public static RequestMatcher onManagementPort(ObjectProvider<ManagementPortMetrics> metrics) {
    return request -> {
      ManagementPortMetrics ports = metrics.getIfAvailable();
      return ports != null
          && ports.isManagementPort(request.getLocalPort())
          && METRIC_PATHS.matches(request);
    };
  }
}
