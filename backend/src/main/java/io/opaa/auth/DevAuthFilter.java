package io.opaa.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates every request as a configured development user by placing a synthetic {@link Jwt}
 * into the security context. No token is parsed and no signature is verified.
 *
 * <p>This is what makes the {@code dev} profile a real authentication mode rather than a hole in
 * the configuration: everything downstream of the filter chain — {@link UserProvisioningFilter},
 * method security, space permissions — sees exactly the same {@code Jwt} principal it would see
 * under {@code oidc}, so the application code has no notion of the active auth mode.
 *
 * <p>The user is selected per request via the {@value #DEV_USER_HEADER} header, which lets tests
 * exercise multiple users (including non-privileged ones) against a single running instance.
 * Without the header the configured default user is used. An unknown subject is rejected with 401
 * rather than silently falling back, so a typo in a test does not quietly run as the wrong user.
 */
public class DevAuthFilter extends OncePerRequestFilter {

  public static final String DEV_USER_HEADER = "X-OPAA-Dev-User";

  private static final Duration TOKEN_VALIDITY = Duration.ofHours(1);

  private final AuthProperties.DevAuth devAuth;

  public DevAuthFilter(AuthProperties.DevAuth devAuth) {
    this.devAuth = devAuth;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String requestedSubject = request.getHeader(DEV_USER_HEADER);
    String subject = requestedSubject == null ? devAuth.defaultUser() : requestedSubject;

    AuthProperties.DevUser user = devAuth.findUser(subject).orElse(null);
    if (user == null) {
      response.sendError(
          HttpServletResponse.SC_UNAUTHORIZED,
          "Unknown dev user '" + subject + "'; configure it under opaa.auth.dev.users");
      return;
    }

    SecurityContextHolder.getContext().setAuthentication(authenticationFor(user));
    filterChain.doFilter(request, response);
  }

  private JwtAuthenticationToken authenticationFor(AuthProperties.DevUser user) {
    Instant issuedAt = Instant.now();
    Jwt jwt =
        new Jwt(
            "dev-" + user.subject(),
            issuedAt,
            issuedAt.plus(TOKEN_VALIDITY),
            Map.of("alg", "none"),
            Map.of(
                "sub", user.subject(),
                "iss", devAuth.issuer(),
                "email", user.email(),
                "name", user.displayName()));
    return new JwtAuthenticationToken(jwt, List.of());
  }
}
