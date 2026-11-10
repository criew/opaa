package io.opaa.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Takes the request id of {@link RequestIds#HEADER} or generates one, binds it to the MDC for the
 * whole filter chain and answers it in the same header before anything else can commit the
 * response. Ordered first, so the security chain's refusals carry it too.
 *
 * <p>The chain sees no trace context of the caller ({@code traceparent}, {@code tracestate}, {@code
 * baggage}, B3): a caller's trace id would otherwise become the trace of the request, and the ratio
 * sampler, which decides on that id, would sample whatever the caller chose.
 */
@Component
@Order(RequestIdFilter.ORDER)
public class RequestIdFilter extends OncePerRequestFilter {

  /**
   * First of all filters. {@code ORDER + 1} is Spring's server observation; a filter that must come
   * early takes {@code ORDER + 2} or later, so no two filters share an order.
   */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE;

  /** Trace context headers of W3C and B3, lower case; any {@code x-b3-*} header as well. */
  static final Set<String> TRACE_CONTEXT_HEADERS =
      Set.of("traceparent", "tracestate", "baggage", "b3");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String requestId = RequestIds.acceptOrGenerate(request.getHeader(RequestIds.HEADER));
    response.setHeader(RequestIds.HEADER, requestId);
    String previous = MDC.get(RequestIds.MDC_KEY);
    MDC.put(RequestIds.MDC_KEY, requestId);
    try {
      filterChain.doFilter(new WithoutTraceContext(request), response);
    } finally {
      if (previous == null) {
        MDC.remove(RequestIds.MDC_KEY);
      } else {
        MDC.put(RequestIds.MDC_KEY, previous);
      }
    }
  }

  static boolean isTraceContext(String header) {
    String name = header.toLowerCase(Locale.ROOT);
    return TRACE_CONTEXT_HEADERS.contains(name) || name.startsWith("x-b3-");
  }

  /** The request without the caller's trace context headers. */
  private static final class WithoutTraceContext extends HttpServletRequestWrapper {

    WithoutTraceContext(HttpServletRequest request) {
      super(request);
    }

    @Override
    public String getHeader(String name) {
      return isTraceContext(name) ? null : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
      return isTraceContext(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
      return Collections.enumeration(
          Collections.list(super.getHeaderNames()).stream()
              .filter(name -> !isTraceContext(name))
              .toList());
    }

    @Override
    public long getDateHeader(String name) {
      return isTraceContext(name) ? -1 : super.getDateHeader(name);
    }

    @Override
    public int getIntHeader(String name) {
      return isTraceContext(name) ? -1 : super.getIntHeader(name);
    }
  }
}
