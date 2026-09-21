package io.opaa.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

public class UserProvisioningFilter extends OncePerRequestFilter {

  /**
   * The {@code error_description} of an account the directory synchronisation locked (#1818) - the
   * same channel the local issuer's refusals use ({@code io.opaa.auth.local.LocalTokenMarkers}), so
   * the SPA tells it apart from an expired token, starts no renewal and names reason and contact
   * instead of redirecting silently.
   */
  static final String ACCOUNT_LOCKED_BY_DIRECTORY = "account_locked:directory";

  private final UserService userService;

  public UserProvisioningFilter(UserService userService) {
    this.userService = userService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
      User user = userService.provisionFromToken(jwt);
      // #1818: a still valid token of a locked account reaches nothing. Checked here rather than
      // at every endpoint, and on every request rather than once per session, so the lock takes
      // effect with the next request instead of with the next sign-in.
      if (user.isDirectoryLocked()) {
        refuse(response);
        return;
      }
      request.setAttribute(CurrentUserArgumentResolver.REQUEST_ATTRIBUTE, CurrentUser.from(user));

      Collection<GrantedAuthority> authorities = new ArrayList<>(authentication.getAuthorities());
      authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getSystemRole().name()));
      JwtAuthenticationToken enriched = new JwtAuthenticationToken(jwt, authorities);
      SecurityContextHolder.getContext().setAuthentication(enriched);
    }
    filterChain.doFilter(request, response);
  }

  private void refuse(HttpServletResponse response) throws IOException {
    SecurityContextHolder.clearContext();
    response.setHeader(
        HttpHeaders.WWW_AUTHENTICATE,
        "Bearer error=\"invalid_token\", error_description=\""
            + ACCOUNT_LOCKED_BY_DIRECTORY
            + "\"");
    response.sendError(HttpStatus.UNAUTHORIZED.value());
  }
}
