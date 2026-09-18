package io.opaa.auth;

import io.opaa.externalaccess.ExternalAccessNetworkPolicy;
import io.opaa.externalaccess.token.ExternalAccessTokenAuthenticator;
import io.opaa.externalaccess.token.ExternalAccessTokenAuthenticator.Result;
import io.opaa.externalaccess.token.ExternalAccessTokenRejection;
import io.opaa.externalaccess.token.ExternalAccessTokenService;
import io.opaa.externalaccess.token.ExternalAccessTokenValues;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a bearer value carrying the access-token prefix (ADR-0035, Entscheidung 2) and
 * attaches the person plus the token id to the request. Sits in its own filter chain ({@link
 * ExternalAccessSecurityConfig}), because the resource server's bearer filter would reject an
 * opaque value as a malformed JWT before it ever got here, and because {@code DevAuthFilter} would
 * authenticate the request as the development user under {@code local,dev}.
 *
 * <p>A refusal answers {@code 401} with the reason in {@code WWW-Authenticate}, the way the local
 * issuer does (ADR-0033, Entscheidung 8) - a tool that gets {@code channel_closed} waits, one that
 * gets {@code token_revoked} needs a new token, and neither has to guess.
 *
 * <p>The authority is deliberately not the person's system role: an access token reaches the paths
 * of {@link ExternalAccessPathAllowlist} and nothing else, whatever the person may do in the web
 * interface. And nothing here logs the value or its prefix, on any level.
 */
public class ExternalAccessTokenAuthenticationFilter extends OncePerRequestFilter {

  /** The authority every access-token call carries, and the only one it carries. */
  public static final String AUTHORITY = "ROLE_EXTERNAL_ACCESS_TOKEN";

  /** Request attribute holding the token id, for the per-call scope check of #1720/#1721. */
  public static final String TOKEN_ID_ATTRIBUTE =
      ExternalAccessTokenAuthenticationFilter.class.getName() + ".tokenId";

  private final ExternalAccessTokenAuthenticator authenticator;
  private final ExternalAccessTokenService tokenService;
  private final ExternalAccessNetworkPolicy networkPolicy;

  public ExternalAccessTokenAuthenticationFilter(
      ExternalAccessTokenAuthenticator authenticator,
      ExternalAccessTokenService tokenService,
      ExternalAccessNetworkPolicy networkPolicy) {
    this.authenticator = authenticator;
    this.tokenService = tokenService;
    this.networkPolicy = networkPolicy;
  }

  /** Whether the request presents a bearer value shaped like an access token. */
  public static boolean carriesAccessToken(HttpServletRequest request) {
    return bearerValue(request) != null;
  }

  private static String bearerValue(HttpServletRequest request) {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return null;
    }
    String value = header.substring(7).trim();
    return ExternalAccessTokenValues.looksLikeAccessToken(value) ? value : null;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String value = bearerValue(request);
    if (value == null) {
      refuse(response, ExternalAccessTokenRejection.INVALID_TOKEN.marker());
      return;
    }
    if (!networkPolicy.isAllowed(request)) {
      // The network restriction belongs to the channel, not to the token (ADR-0035); it is checked
      // before the lookup, so an address outside it learns nothing about which values exist.
      refuse(response, ExternalAccessTokenRejection.NETWORK_NOT_ALLOWED.marker());
      return;
    }
    Result result = authenticator.authenticate(value);
    if (result instanceof Result.Refused refused) {
      refuse(response, refused.rejection().marker());
      return;
    }
    Result.Authenticated authenticated = (Result.Authenticated) result;
    User user = authenticated.user();
    UUID tokenId = authenticated.tokenId();
    request.setAttribute(CurrentUserArgumentResolver.REQUEST_ATTRIBUTE, CurrentUser.from(user));
    request.setAttribute(TOKEN_ID_ATTRIBUTE, tokenId);
    SecurityContextHolder.getContext()
        .setAuthentication(new ExternalAccessTokenAuthentication(user.getId(), tokenId));
    // The day of use, at most one write per day - not an event, not a counter (ADR-0035).
    tokenService.recordUse(tokenId);
    filterChain.doFilter(request, response);
  }

  private void refuse(HttpServletResponse response, String marker) throws IOException {
    SecurityContextHolder.clearContext();
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setHeader(
        HttpHeaders.WWW_AUTHENTICATE,
        "Bearer error=\"invalid_token\", error_description=\"" + marker + "\"");
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write("{\"error\":\"Nicht angemeldet\",\"status\":401}");
  }

  /** The authentication an access-token call carries: the person, the token, one authority. */
  static final class ExternalAccessTokenAuthentication extends AbstractAuthenticationToken {

    private final UUID userId;
    private final transient UUID tokenId;

    ExternalAccessTokenAuthentication(UUID userId, UUID tokenId) {
      super(List.of(new SimpleGrantedAuthority(AUTHORITY)));
      this.userId = userId;
      this.tokenId = tokenId;
      setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
      // Never the presented value: an authentication object ends up in error reports and dumps.
      return "";
    }

    @Override
    public Object getPrincipal() {
      return userId;
    }

    UUID tokenId() {
      return tokenId;
    }
  }
}
