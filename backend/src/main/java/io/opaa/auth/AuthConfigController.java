package io.opaa.auth;

import io.opaa.api.dto.AuthConfigResponse;
import io.opaa.api.dto.OidcSignInProvider;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the sign-in page needs before there is a session (ADR-0025, Entscheidung 5): the mode and,
 * in the {@code oidc} mode, every enabled provider in sign-in order. Enabled, not "decoder ready":
 * the browser talks to the provider's discovery and authorization endpoints itself, and a provider
 * whose keys the backend could not fetch at start-up (Keycloak regularly starts after OPAA) is
 * retried on the first token of that very sign-in - listing only ready providers would lock every
 * visitor out until a restart. Readable without authentication and deliberately limited to what
 * every visitor sees on the sign-in page.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthConfigController {

  private static final String OIDC_MODE = "oidc";

  private final AuthProperties authProperties;
  private final OidcProviderRepository providerRepository;

  public AuthConfigController(
      AuthProperties authProperties, OidcProviderRepository providerRepository) {
    this.authProperties = authProperties;
    this.providerRepository = providerRepository;
  }

  @GetMapping("/config")
  public AuthConfigResponse getAuthConfig() {
    String mode = authProperties.mode();
    List<OidcSignInProvider> providers =
        OIDC_MODE.equals(mode)
            ? providerRepository.findAllByEnabledTrueOrderBySortOrderAscDisplayNameAsc().stream()
                .map(this::toSignInProvider)
                .toList()
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
