package io.opaa.ratelimit;

import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import java.util.function.Supplier;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Marks a request whose operation refused it before doing any work - the caller may not, or the
 * object is not there - so {@link RateLimitFilter} gives back its token of the global ceiling. Its
 * budgets per client and per account stay spent. Only the checks in front of the work run through
 * {@link #check}: a {@code 404} after an outbound fetch failed did the work and keeps its token.
 * Outside a request (a scheduled run) nothing is marked.
 */
public final class RefusedBeforeWork {

  static final String ATTRIBUTE = RefusedBeforeWork.class.getName() + ".MARK";

  private RefusedBeforeWork() {}

  /**
   * Runs the rights and existence checks in front of the work; a {@link NotFoundException} or
   * {@link AccessDeniedException} they throw marks the current request before it propagates.
   */
  public static <T> T check(Supplier<T> checks) {
    try {
      return checks.get();
    } catch (NotFoundException | AccessDeniedException refused) {
      mark();
      throw refused;
    }
  }

  private static void mark() {
    RequestAttributes request = RequestContextHolder.getRequestAttributes();
    if (request != null) {
      request.setAttribute(ATTRIBUTE, Boolean.TRUE, RequestAttributes.SCOPE_REQUEST);
    }
  }
}
