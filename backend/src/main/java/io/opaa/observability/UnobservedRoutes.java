package io.opaa.observability;

import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API routes that leave no trace in logs or metrics: calling them is itself information about a
 * person. The request line ({@code RequestLoggingFilter}), the frontend's nginx access log and the
 * HTTP server meters all skip {@link #QUICK_TOUR_HINT}, the marker of the quick-tour hint, so
 * nothing records when someone dealt with the hint.
 */
@Configuration(proxyBeanMethods = false)
public class UnobservedRoutes {

  /** The path of the marker of the quick-tour hint, as served and as the {@code uri} meter tag. */
  public static final String QUICK_TOUR_HINT = "/api/v1/me/quick-tour-hint";

  static final String HTTP_SERVER_REQUESTS = "http.server.requests";

  /** Denies every {@code http.server.requests*} meter whose {@code uri} tag is such a route. */
  @Bean
  MeterFilter unobservedRoutesMeterFilter() {
    return MeterFilter.deny(
        id ->
            id.getName().startsWith(HTTP_SERVER_REQUESTS)
                && QUICK_TOUR_HINT.equals(id.getTag("uri")));
  }
}
