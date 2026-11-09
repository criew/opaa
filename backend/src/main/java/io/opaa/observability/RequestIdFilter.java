package io.opaa.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Takes the request id of {@link RequestIds#HEADER} or generates one, binds it to the MDC for the
 * whole filter chain and answers it in the same header before anything else can commit the
 * response. Ordered first, so the security chain's refusals carry it too.
 */
@Component
@Order(RequestIdFilter.ORDER)
public class RequestIdFilter extends OncePerRequestFilter {

  /** First of all filters; a filter that must come early takes {@code ORDER + 1} or later. */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String requestId = RequestIds.acceptOrGenerate(request.getHeader(RequestIds.HEADER));
    response.setHeader(RequestIds.HEADER, requestId);
    String previous = MDC.get(RequestIds.MDC_KEY);
    MDC.put(RequestIds.MDC_KEY, requestId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      if (previous == null) {
        MDC.remove(RequestIds.MDC_KEY);
      } else {
        MDC.put(RequestIds.MDC_KEY, previous);
      }
    }
  }
}
