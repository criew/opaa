package io.opaa.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

public class UserProvisioningFilter extends OncePerRequestFilter {

  /**
   * The {@code error_description} of a request whose account the directory run has blocked (#1818),
   * next to the markers of the local issuer ({@code io.opaa.auth.local.LocalTokenMarkers}) and the
   * registry's {@code unknown_issuer}. The SPA tells it apart from an expired token, starts no
   * renewal and shows the person the reason and where to turn - "Grund und Ansprechstelle"
   * (ADR-0036, Entscheidung 3; Personalrat C3), not a bare refusal.
   */
  public static final String ACCOUNT_BLOCKED_MARKER = "account_blocked:directory";

  private final UserService userService;
  private final AccountStateService accountState;

  public UserProvisioningFilter(UserService userService, AccountStateService accountState) {
    this.userService = userService;
    this.accountState = accountState;
  }

  /**
   * A blocked account is refused here rather than in a decoder of its own: this is the one filter
   * every bearer token of every issuer passes, and it has already resolved the account the token
   * names - a check in the provider's decoder would repeat that lookup on every request. The
   * refusal is an {@link OAuth2AuthenticationException} because this filter still sits before
   * {@code ExceptionTranslationFilter}, which turns it into the {@code 401} with {@code
   * WWW-Authenticate} the local issuer's own refusals use.
   */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
      User user = userService.provisionFromToken(jwt);
      if (accountState.isBlocked(user.getId())) {
        throw new OAuth2AuthenticationException(
            new BearerTokenError(
                OAuth2ErrorCodes.INVALID_TOKEN,
                HttpStatus.UNAUTHORIZED,
                ACCOUNT_BLOCKED_MARKER,
                null));
      }
      request.setAttribute(CurrentUserArgumentResolver.REQUEST_ATTRIBUTE, CurrentUser.from(user));

      Collection<GrantedAuthority> authorities = new ArrayList<>(authentication.getAuthorities());
      authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getSystemRole().name()));
      JwtAuthenticationToken enriched = new JwtAuthenticationToken(jwt, authorities);
      SecurityContextHolder.getContext().setAuthentication(enriched);
    }
    filterChain.doFilter(request, response);
  }
}
