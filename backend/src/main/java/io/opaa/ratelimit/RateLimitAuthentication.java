package io.opaa.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Tells {@link RateLimitFilter}, which runs before the security chain, that a request did
 * authenticate - set by {@link AuthenticatedRequestMarkerFilter} right behind the chain, and by a
 * handler that authenticates without a session (the push intake) once its own check passed. A
 * request without the mark is treated as unauthenticated, whatever its status code.
 */
public final class RateLimitAuthentication {

  static final String SESSION_ATTRIBUTE = RateLimitAuthentication.class.getName() + ".SESSION";
  static final String HANDLER_ATTRIBUTE = RateLimitAuthentication.class.getName() + ".HANDLER";

  private RateLimitAuthentication() {}

  /** Marks {@code request} as authenticated by its handler's own check (the push secret). */
  public static void markAuthenticatedByHandler(HttpServletRequest request) {
    request.setAttribute(HANDLER_ATTRIBUTE, Boolean.TRUE);
  }

  /**
   * Marks {@code request} as authenticated by the security chain - the job of {@link
   * AuthenticatedRequestMarkerFilter}; elsewhere only for tests that stand in for the chain.
   */
  public static void markAuthenticatedBySession(HttpServletRequest request) {
    request.setAttribute(SESSION_ATTRIBUTE, Boolean.TRUE);
  }

  static boolean isMarked(HttpServletRequest request, String attribute) {
    return Boolean.TRUE.equals(request.getAttribute(attribute));
  }
}
