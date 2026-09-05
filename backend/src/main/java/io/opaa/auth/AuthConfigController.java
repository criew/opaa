package io.opaa.auth;

import io.opaa.api.dto.AuthConfigResponse;
import io.opaa.api.dto.OidcSignInProvider;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRegistry;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the sign-in page needs before there is a session (ADR-0025, Entscheidung 5): the mode and,
 * in the {@code oidc} mode, the enabled providers whose decoder is ready - a provider whose keys
 * the backend cannot fetch is left out, because a token of it would be refused anyway. Readable
 * without authentication and deliberately limited to what every visitor sees on the sign-in page.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthConfigController {

  private static final String OIDC_MODE = "oidc";

  private final AuthProperties authProperties;
  private final OidcProviderRegistry providerRegistry;

  public AuthConfigController(
      AuthProperties authProperties, OidcProviderRegistry providerRegistry) {
    this.authProperties = authProperties;
    this.providerRegistry = providerRegistry;
  }

  @GetMapping("/config")
  public AuthConfigResponse getAuthConfig() {
    String mode = authProperties.mode();
    List<OidcSignInProvider> providers =
        OIDC_MODE.equals(mode)
            ? providerRegistry.enabledProviders().stream().map(this::toSignInProvider).toList()
            : List.of();
    return new AuthConfigResponse(mode, providers);
  }

  private OidcSignInProvider toSignInProvider(OidcProvider provider) {
    return new OidcSignInProvider(
        provider.getId(),
        provider.getDisplayName(),
        provider.getIssuerUri(),
        provider.getClientId(),
        provider.isDefaultProvider(),
        provider.getSortOrder());
  }
}
