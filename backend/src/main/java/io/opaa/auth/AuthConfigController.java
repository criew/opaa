package io.opaa.auth;

import io.opaa.auth.dto.AuthConfigResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthConfigController {

  private final AuthProperties authProperties;

  public AuthConfigController(AuthProperties authProperties) {
    this.authProperties = authProperties;
  }

  @GetMapping("/config")
  public AuthConfigResponse getAuthConfig() {
    String mode = authProperties.mode();
    if ("oidc".equals(mode)) {
      AuthProperties.OidcAuth oidc = authProperties.oidc();
      return new AuthConfigResponse(
          mode, oidc != null ? oidc.authority() : null, oidc != null ? oidc.clientId() : null);
    }
    return new AuthConfigResponse(mode, null, null);
  }
}
