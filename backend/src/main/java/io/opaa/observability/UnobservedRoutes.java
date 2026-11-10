package io.opaa.observability;

import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.ObservationPredicate;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UriUtils;

/**
 * API routes that leave no trace in logs, metrics or traces: calling them is itself information
 * about a person. The request line ({@code RequestLoggingFilter}), the frontend's nginx access log,
 * the HTTP server meters and every observation of such a request - so no span either - skip {@link
 * #QUICK_TOUR_HINT}, the marker of the quick-tour hint, so nothing records when someone dealt with
 * the hint.
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

  /**
   * Denies the server observation of such a route and every observation started while one is served
   * on the thread (security chain, database, model), so none becomes a span of its own.
   */
  @Bean
  ObservationPredicate unobservedRoutesObservationPredicate() {
    return (name, context) -> {
      if (context instanceof ServerRequestObservationContext server) {
        return !isUnobserved(server.getCarrier());
      }
      return !(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes bound
          && isUnobserved(bound.getRequest()));
    };
  }

  /** Whether {@code request} goes to one of the routes, in any spelling of its path. */
  public static boolean isUnobserved(HttpServletRequest request) {
    return request != null
        && QUICK_TOUR_HINT.equals(UriUtils.decode(request.getRequestURI(), StandardCharsets.UTF_8));
  }
}
