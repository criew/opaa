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
 * Directly behind the security chain: checks the budget per account of the request's rule, then
 * marks an authenticated request for {@link RateLimitFilter}. A request refused here or by the
 * chain stays unmarked and so gives back what it took before the chain.
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
