package io.opaa.auth;

import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthConfigController {

  @Value("${opaa.auth.mode:mock}")
  private String authMode;

  @Value("${opaa.auth.oidc.authority:}")
  private String oidcAuthority;

  @Value("${opaa.auth.oidc.client-id:}")
  private String oidcClientId;

  @GetMapping("/config")
  public Map<String, Object> getAuthConfig() {
    Map<String, Object> config = new HashMap<>();
    config.put("mode", authMode);
    if ("oidc".equals(authMode)) {
      config.put("authority", oidcAuthority);
      config.put("clientId", oidcClientId);
    }
    return config;
  }
}
