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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Authenticates a bearer value carrying the access-token prefix (ADR-0035, Entscheidung 2) and
 * attaches the person plus the token id to the request. Sits in its own filter chain ({@link
 * ExternalAccessSecurityConfig}), because the resource server's bearer filter would reject an
 * opaque value as a malformed JWT before it ever got here, and because {@code DevAuthFilter} would
 * authenticate the request as the development user under {@code local,dev}.
 *
 * <p>A refusal answers {@code 401} with the reason in {@code WWW-Authenticate}, the way the local
 * issuer does (ADR-0033, Entscheidung 8) - a tool that gets {@code channel_closed} waits, one that
 * gets {@code token_revoked} needs a new token, and neither has to guess. A path whose
 * specification demands another answer registers an {@link ExternalAccessRefusalStyle}; the MCP
 * endpoint is the one that does (#1721).
 *
 * <p><b>The caller a token call carries is never the person's full identity.</b> Neither the
 * authority ({@link #AUTHORITY}, never the system role) nor the {@link CurrentUser} of the request
 * carries it: the {@code CurrentUser} is built with the lowest system role, so an endpoint that
 * authorises out of {@code @Caller} rather than out of {@code @PreAuthorize} cannot hand a
 * Systemverwalterin her administrative rights through her access token.
 *
 * <p>"Zuletzt benutzt" is written <b>after</b> a successful chain, and only when the day is not
 * already recorded - a refused call is no use, and the check costs no query on the hot path because
 * the authenticator already read the value.
 *
 * <p>Nothing here logs the value or its prefix, on any level.
 */
public class ExternalAccessTokenAuthenticationFilter extends OncePerRequestFilter {

  /** The authority every access-token call carries, and the only one it carries. */
  public static final String AUTHORITY = "ROLE_EXTERNAL_ACCESS_TOKEN";

  /** Request attribute holding the token id, for the per-call scope check of #1720/#1721. */
  public static final String TOKEN_ID_ATTRIBUTE =
      ExternalAccessTokenAuthenticationFilter.class.getName() + ".tokenId";

  private static final String UNAUTHENTICATED_MESSAGE = "Nicht angemeldet";

  private final ExternalAccessTokenAuthenticator authenticator;
  private final ExternalAccessTokenService tokenService;
  private final ExternalAccessNetworkPolicy networkPolicy;
  private final List<ExternalAccessRefusalStyle> refusalStyles;
  private final JsonMapper jsonMapper;
  private final Clock clock;
  private final ZoneId zone;

  public ExternalAccessTokenAuthenticationFilter(
      ExternalAccessTokenAuthenticator authenticator,
      ExternalAccessTokenService tokenService,
      ExternalAccessNetworkPolicy networkPolicy,
      List<ExternalAccessRefusalStyle> refusalStyles,
      JsonMapper jsonMapper,
      Clock clock) {
    this.authenticator = authenticator;
    this.tokenService = tokenService;
    this.networkPolicy = networkPolicy;
    this.refusalStyles = List.copyOf(refusalStyles);
    this.jsonMapper = jsonMapper;
    this.clock = clock;
    this.zone = ZoneId.systemDefault();
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
    ExternalAccessRefusalStyle style = styleFor(request);
    String value = bearerValue(request);
    if (value == null) {
      refuse(response, style, ExternalAccessTokenRejection.INVALID_TOKEN);
      return;
    }
    if (!networkPolicy.isAllowed(request)) {
      // The network restriction belongs to the channel, not to the token (ADR-0035); it is checked
      // before the lookup, so an address outside it learns nothing about which values exist.
      refuse(response, style, ExternalAccessTokenRejection.NETWORK_NOT_ALLOWED);
      return;
    }
    Result result =
        style != null && style.distinguishesClosedChannel()
            ? authenticator.authenticateWithSwitchLast(value)
            : authenticator.authenticate(value);
    if (result instanceof Result.Refused refused) {
      refuse(response, style, refused.rejection());
      return;
    }
    Result.Authenticated authenticated = (Result.Authenticated) result;
    UUID tokenId = authenticated.tokenId();
    request.setAttribute(
        CurrentUserArgumentResolver.REQUEST_ATTRIBUTE,
        CurrentUser.forExternalAccess(authenticated.user()));
    request.setAttribute(TOKEN_ID_ATTRIBUTE, tokenId);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new ExternalAccessTokenAuthentication(authenticated.user().getId(), tokenId));
    filterChain.doFilter(request, response);
    recordUse(authenticated, response);
  }

  /**
   * The day of use - at most one write per day, never for a refused call, and no extra query: the
   * authenticator already read the recorded day.
   */
  private void recordUse(Result.Authenticated authenticated, HttpServletResponse response) {
    if (response.getStatus() >= HttpStatus.BAD_REQUEST.value()) {
      return;
    }
    LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
    if (today.equals(authenticated.lastUsedOn())) {
      return;
    }
    tokenService.recordUse(authenticated.tokenId());
  }

  /** The style of the one path that claims this request, or {@code null} for the default. */
  private ExternalAccessRefusalStyle styleFor(HttpServletRequest request) {
    return refusalStyles.stream()
        .filter(style -> style.appliesTo(request))
        .findFirst()
        .orElse(null);
  }

  private void refuse(
      HttpServletResponse response,
      ExternalAccessRefusalStyle style,
      ExternalAccessTokenRejection rejection)
      throws IOException {
    SecurityContextHolder.clearContext();
    if (style != null) {
      style.refuse(response, rejection);
      return;
    }
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setHeader(
        HttpHeaders.WWW_AUTHENTICATE,
        "Bearer error=\"invalid_token\", error_description=\"" + rejection.marker() + "\"");
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    // The shape of ErrorResponse in the specification: error, status and timestamp.
    jsonMapper.writeValue(
        response.getOutputStream(),
        Map.of(
            "error",
            UNAUTHENTICATED_MESSAGE,
            "status",
            HttpStatus.UNAUTHORIZED.value(),
            "timestamp",
            Instant.now().toString()));
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
