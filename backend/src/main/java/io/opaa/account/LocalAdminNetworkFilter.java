package io.opaa.account;

import io.opaa.api.dto.ErrorResponse;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.LocalIssuer;
import io.opaa.security.ClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * The network restriction of local system administrators (ADR-0033, Entscheidung 9) on every
 * request, not only at the sign-in: with {@code OPAA_LOCAL_ADMIN_ALLOWED_CIDRS} set, a request with
 * the access token of a local {@code SYSTEM_ADMIN} from outside those networks is answered {@code
 * 403} with code {@value #CODE}; only the sign-out stays open. Runs after {@code
 * AuthorizationFilter}, so the token carries the role the provisioning filter added.
 */
public class LocalAdminNetworkFilter extends OncePerRequestFilter {

  public static final String CODE = "ADMIN_NETWORK_REQUIRED";
  static final String MESSAGE =
      "Ein lokales Konto der Systemverwaltung ist nur aus den dafür freigegebenen Netzen nutzbar.";

  private static final String ADMIN_AUTHORITY = "ROLE_" + SystemRole.SYSTEM_ADMIN.name();

  /** Matched on the decoded path like the handler mapping, so a context path is honoured too. */
  private static final RequestMatcher EXEMPT =
      PathPatternRequestMatcher.withDefaults().matcher("/api/v1/auth/local/logout");

  private final LocalAdminNetworkPolicy networkPolicy;
  private final ClientIpResolver clientIpResolver;
  private final JsonMapper jsonMapper;
  private final Clock clock;

  public LocalAdminNetworkFilter(
      LocalAdminNetworkPolicy networkPolicy,
      ClientIpResolver clientIpResolver,
      JsonMapper jsonMapper,
      Clock clock) {
    this.networkPolicy = networkPolicy;
    this.clientIpResolver = clientIpResolver;
    this.jsonMapper = jsonMapper;
    this.clock = clock;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (networkPolicy.isRestricted()
        && isLocalAdministrator(SecurityContextHolder.getContext().getAuthentication())
        && !EXEMPT.matches(request)
        && !networkPolicy.permitsAdminSignIn(clientIpResolver.resolve(request))) {
      refuse(response);
      return;
    }
    filterChain.doFilter(request, response);
  }

  private static boolean isLocalAdministrator(Authentication authentication) {
    return authentication instanceof JwtAuthenticationToken token
        && LocalIssuer.URN.equals(token.getToken().getClaimAsString("iss"))
        && token.getAuthorities().stream()
            .anyMatch(authority -> ADMIN_AUTHORITY.equals(authority.getAuthority()));
  }

  private void refuse(HttpServletResponse response) throws IOException {
    ErrorResponse body =
        new ErrorResponse(MESSAGE, HttpStatus.FORBIDDEN.value(), Instant.now(clock));
    body.setCode(CODE);
    response.setStatus(HttpStatus.FORBIDDEN.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    jsonMapper.writeValue(response.getOutputStream(), body);
  }
}
