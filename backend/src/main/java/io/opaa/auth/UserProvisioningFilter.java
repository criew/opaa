package io.opaa.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

public class UserProvisioningFilter extends OncePerRequestFilter {

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
      String subject = jwt.getSubject();
      String issuer = jwt.getClaimAsString("iss");
      if (issuer == null || issuer.isBlank()) {
        issuer = "unknown";
      }
      String email = jwt.getClaimAsString("email");
      String displayName = jwt.getClaimAsString("name");
      if (displayName == null) {
        displayName = jwt.getClaimAsString("preferred_username");
      }
      userService.findOrCreateUser(subject, issuer, email, displayName);
    }
    filterChain.doFilter(request, response);
  }
}
