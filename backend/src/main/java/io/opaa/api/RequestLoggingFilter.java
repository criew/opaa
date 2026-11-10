package io.opaa.api;

import io.opaa.observability.UnobservedRoutes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Writes one line per API call: method, path, status and duration, with the request id of the MDC
 * ({@code RequestIdFilter} runs first). Calls to the routes of {@link UnobservedRoutes} write none,
 * so no log shows when someone dealt with the quick-tour hint.
 */
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    long start = System.currentTimeMillis();
    try {
      filterChain.doFilter(request, response);
    } finally {
      long duration = System.currentTimeMillis() - start;
      log.info(
          "{} {} {} {}ms",
          request.getMethod(),
          request.getRequestURI(),
          response.getStatus(),
          duration);
    }
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/") || UnobservedRoutes.isUnobserved(request);
  }
}
