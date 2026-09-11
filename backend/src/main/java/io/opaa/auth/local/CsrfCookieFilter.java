package io.opaa.auth.local;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the deferred {@link CsrfToken} on every response so {@code CookieCsrfTokenRepository}
 * writes the {@code XSRF-TOKEN} cookie (ADR-0033, Entscheidung 7). The SPA reads that cookie and
 * echoes it as {@code X-XSRF-TOKEN} on the two cookie-bearing endpoints; without this filter the
 * lazily created token would never reach the client.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    if (csrfToken != null) {
      csrfToken.getToken();
    }
    filterChain.doFilter(request, response);
  }
}
