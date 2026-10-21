package io.opaa.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Registered directly behind the security chain: a request reaches it only after the chain let it
 * through, and it marks the request for {@link RateLimitFilter} when the chain authenticated it -
 * an anonymous principal does not count. A request the firewall or the chain refused never gets
 * here and so stays unmarked. Before marking it checks the budget per account the request's rule
 * may carry; a request refused there is answered {@code 429} and stays unmarked, so it gives back
 * the tokens it took before the chain.
 */
class AuthenticatedRequestMarkerFilter extends OncePerRequestFilter {

  private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (trustResolver.isAuthenticated(authentication)) {
      if (!RateLimitFilter.admitAccount(request, response, authentication)) {
        return;
      }
      RateLimitAuthentication.markAuthenticatedBySession(request);
      if (authentication instanceof OwnQuotaAuthentication) {
        RateLimitAuthentication.markOwnQuota(request);
      }
    }
    filterChain.doFilter(request, response);
  }
}
