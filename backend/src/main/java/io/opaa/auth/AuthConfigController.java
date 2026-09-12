package io.opaa.auth;

import io.opaa.api.dto.AuthConfigResponse;
import io.opaa.api.dto.LocalAccountsConfig;
import io.opaa.api.dto.OidcSignInProvider;
import io.opaa.auth.local.LocalAuthSettings;
import io.opaa.auth.local.LocalAuthSettingsRepository;
import io.opaa.auth.local.PublicBaseUrlAvailability;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the sign-in page needs before there is a session (ADR-0025, Entscheidung 5): the mode and,
 * in the {@code oidc} mode, every enabled OIDC provider in sign-in order. Enabled, not "decoder
 * ready": the browser talks to the provider's discovery and authorization endpoints itself, and a
 * provider whose keys the backend could not fetch at start-up (Keycloak regularly starts after
 * OPAA) is retried on the first token of that very sign-in - listing only ready providers would
 * lock every visitor out until a restart. Readable without authentication and deliberately limited
 * to what every visitor sees on the sign-in page.
 *
 * <p>The {@code LOCAL} row (ADR-0033, Entscheidung 4) is never a provider here; it is {@code
 * localAccounts}: the switch, and the self-service flows only while the switch, the setting and -
 * for anything that hands out a link - the public base URL allow them (Entscheidung 10).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthConfigController {

  private static final String OIDC_MODE = "oidc";

  private final AuthProperties authProperties;
  private final OidcProviderRepository providerRepository;
  private final LocalAuthSettingsRepository settingsRepository;
  private final ObjectProvider<PublicBaseUrlAvailability> publicBaseUrl;

  public AuthConfigController(
      AuthProperties authProperties,
      OidcProviderRepository providerRepository,
      LocalAuthSettingsRepository settingsRepository,
      ObjectProvider<PublicBaseUrlAvailability> publicBaseUrl) {
    this.authProperties = authProperties;
    this.providerRepository = providerRepository;
    this.settingsRepository = settingsRepository;
    this.publicBaseUrl = publicBaseUrl;
  }

  @GetMapping("/config")
  public AuthConfigResponse getAuthConfig() {
    String mode = authProperties.mode();
    List<OidcSignInProvider> providers =
        OIDC_MODE.equals(mode)
            ? providerRepository.findAllByEnabledTrueOrderBySortOrderAscDisplayNameAsc().stream()
                .filter(provider -> !provider.isLocal())
                .map(this::toSignInProvider)
                .toList()
            : List.of();
    return new AuthConfigResponse(mode, providers, localAccounts(mode));
  }

  private LocalAccountsConfig localAccounts(String mode) {
    boolean enabled =
        OIDC_MODE.equals(mode)
            && providerRepository
                .findByNormalizedIssuerUri(LocalIssuer.URN)
                .map(OidcProvider::isEnabled)
                .orElse(false);
    LocalAuthSettings.Values settings =
        settingsRepository
            .findSingleton()
            .map(LocalAuthSettings::values)
            .orElseGet(LocalAuthSettings.Values::defaults);
    boolean linksPossible =
        publicBaseUrl.getIfAvailable(() -> PublicBaseUrlAvailability.NONE).isConfigured();
    return new LocalAccountsConfig(
        enabled,
        enabled
            && linksPossible
            && settings.selfRegistrationEnabled()
            && !settings.selfRegistrationAllowedDomains().isEmpty(),
        enabled && linksPossible && settings.passwordResetEnabled(),
        settings.passwordMinLength());
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
