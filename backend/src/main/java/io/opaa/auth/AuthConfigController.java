package io.opaa.auth;

import io.opaa.api.dto.AuthConfigResponse;
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
    AuthConfigResponse response = new AuthConfigResponse().mode(mode);
    if ("oidc".equals(mode)) {
      AuthProperties.OidcAuth oidc = authProperties.oidc();
      if (oidc != null) {
        response.authority(oidc.authority()).clientId(oidc.clientId());
      }
    }
    return response;
  }
}
