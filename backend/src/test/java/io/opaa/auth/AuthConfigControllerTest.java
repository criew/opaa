package io.opaa.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.local.LocalAuthSettings;
import io.opaa.auth.local.LocalAuthSettingsRepository;
import io.opaa.auth.local.LocalSelfServiceAvailability;
import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /api/v1/auth/config} (#1332, ADR-0025 Entscheidung 5; #1533, ADR-0033 Entscheidung 4):
 * the mode, in the {@code oidc} mode every enabled provider in sign-in page order - whether or not
 * the backend's decoder for it is ready yet - with exactly the fields the sign-in page shows, and
 * the local account management as {@code localAccounts}: the switch of the LOCAL row (never listed
 * among the providers), and the self-service flows exactly as {@link LocalSelfServiceAvailability}
 * reports them - the same answer the authorization rule and the rate limiter act on, not a second
 * computation of the conjunction here (#1592; the conjunction itself is {@code
 * LocalSelfServiceServiceTest}'s subject). The mode stays this endpoint's own condition.
 */
@WebMvcTest(AuthConfigController.class)
@Import({TestSecurityConfig.class, AuthConfigControllerTest.FlowsStub.class})
class AuthConfigControllerTest {

  @TestConfiguration
  static class FlowsStub {
    static boolean passwordReset;
    static boolean selfRegistration;

    @Bean
    LocalSelfServiceAvailability localSelfServiceAvailability() {
      return new LocalSelfServiceAvailability() {

        @Override
        public boolean isPasswordResetAvailable() {
          return passwordReset;
        }

        @Override
        public boolean isSelfRegistrationAvailable() {
          return selfRegistration;
        }
      };
    }
  }

  @Autowired private MockMvc mockMvc;
  @MockitoBean private AuthProperties authProperties;
  @MockitoBean private OidcProviderRepository providerRepository;
  @MockitoBean private LocalAuthSettingsRepository settingsRepository;

  // TestSecurityConfig's UserProvisioningFilter needs a UserService bean even though this
  // unauthenticated endpoint never calls it.
  @MockitoBean private UserService userService;

  private LocalAuthSettings settings;

  @BeforeEach
  void setUp() {
    FlowsStub.passwordReset = false;
    FlowsStub.selfRegistration = false;
    settings = mock(LocalAuthSettings.class);
    when(settings.values()).thenReturn(LocalAuthSettings.Values.defaults());
    when(settingsRepository.findSingleton()).thenReturn(Optional.of(settings));
    when(providerRepository.findByNormalizedIssuerUri(any())).thenReturn(Optional.empty());
  }

  @Test
  void inTheDevModeThereAreNoProvidersAndLocalAccountsAreOff() throws Exception {
    when(authProperties.mode()).thenReturn("dev");
    // even if the flows reported themselves available: outside oidc there is no chain to serve them
    FlowsStub.passwordReset = true;
    FlowsStub.selfRegistration = true;

    mockMvc
        .perform(get("/api/v1/auth/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("dev"))
        .andExpect(jsonPath("$.providers").isEmpty())
        .andExpect(jsonPath("$.localAccounts.enabled").value(false))
        .andExpect(jsonPath("$.localAccounts.selfRegistrationEnabled").value(false))
        .andExpect(jsonPath("$.localAccounts.passwordResetEnabled").value(false))
        .andExpect(jsonPath("$.localAccounts.passwordMinLength").value(12));
  }

  @Test
  void inTheOidcModeEveryEnabledProviderIsListedInSignInOrderWithItsPublicFields()
      throws Exception {
    when(authProperties.mode()).thenReturn("oidc");
    OidcProvider standard =
        new OidcProvider(
            "Beschäftigte",
            "https://idp.example/realms/a",
            "opaa-frontend",
            "http://keycloak:8180/certs",
            new OidcClaimMapping(null, null, "realm_access.roles", "opaa-admin", null, null));
    standard.markDefault();
    OidcProvider partner =
        new OidcProvider(
            "Partner",
            "https://partner.example/realms/b/",
            "opaa-partner",
            null,
            OidcClaimMapping.keycloakDefaults());
    partner.setSortOrder(1);
    // the partner's decoder is not built yet (its IdP may still be starting) - it is listed anyway
    when(providerRepository.findAllByEnabledTrueOrderBySortOrderAscDisplayNameAsc())
        .thenReturn(List.of(standard, partner));

    mockMvc
        .perform(get("/api/v1/auth/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("oidc"))
        .andExpect(jsonPath("$.providers.length()").value(2))
        .andExpect(jsonPath("$.providers[0].id").value(standard.getId().toString()))
        .andExpect(jsonPath("$.providers[0].displayName").value("Beschäftigte"))
        .andExpect(jsonPath("$.providers[0].issuerUri").value("https://idp.example/realms/a"))
        .andExpect(jsonPath("$.providers[0].clientId").value("opaa-frontend"))
        .andExpect(jsonPath("$.providers[0].isDefault").value(true))
        .andExpect(jsonPath("$.providers[0].sortOrder").value(0))
        .andExpect(jsonPath("$.providers[1].displayName").value("Partner"))
        .andExpect(jsonPath("$.providers[1].issuerUri").value("https://partner.example/realms/b/"))
        .andExpect(jsonPath("$.providers[1].isDefault").value(false))
        .andExpect(jsonPath("$.providers[1].sortOrder").value(1))
        // nothing beyond the sign-in page's needs: no claim mapping, no backend-side address
        .andExpect(jsonPath("$.providers[0].jwkSetUri").doesNotExist())
        .andExpect(jsonPath("$.providers[0].claimMapping").doesNotExist())
        .andExpect(jsonPath("$.providers[0].rolesClaim").doesNotExist())
        .andExpect(jsonPath("$.localAccounts.enabled").value(false));
  }

  @Test
  void theLocalRowIsNeverAProviderAndTheFlowsAreTheOneAvailabilityAnswer() throws Exception {
    when(authProperties.mode()).thenReturn("oidc");
    OidcProvider standard =
        new OidcProvider(
            "Beschäftigte",
            "https://idp.example/realms/a",
            "opaa-frontend",
            null,
            OidcClaimMapping.keycloakDefaults());
    OidcProvider local = OidcProvider.localProvider("Lokale Konten");
    local.enable();
    when(providerRepository.findAllByEnabledTrueOrderBySortOrderAscDisplayNameAsc())
        .thenReturn(List.of(standard, local));
    when(providerRepository.findByNormalizedIssuerUri(LocalIssuer.URN))
        .thenReturn(Optional.of(local));
    when(settings.values())
        .thenReturn(
            new LocalAuthSettings.Values(true, List.of("stadt.example"), true, 14, 72, 30, 90, 90));

    // the settings alone offer no flow - what is reported is what the availability answers
    mockMvc
        .perform(get("/api/v1/auth/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.providers.length()").value(1))
        .andExpect(jsonPath("$.providers[0].displayName").value("Beschäftigte"))
        .andExpect(jsonPath("$.localAccounts.enabled").value(true))
        .andExpect(jsonPath("$.localAccounts.selfRegistrationEnabled").value(false))
        .andExpect(jsonPath("$.localAccounts.passwordResetEnabled").value(false))
        .andExpect(jsonPath("$.localAccounts.passwordMinLength").value(14));

    FlowsStub.passwordReset = true;
    FlowsStub.selfRegistration = true;
    mockMvc
        .perform(get("/api/v1/auth/config"))
        .andExpect(jsonPath("$.localAccounts.selfRegistrationEnabled").value(true))
        .andExpect(jsonPath("$.localAccounts.passwordResetEnabled").value(true));

    // each flow is reported on its own
    FlowsStub.selfRegistration = false;
    mockMvc
        .perform(get("/api/v1/auth/config"))
        .andExpect(jsonPath("$.localAccounts.selfRegistrationEnabled").value(false))
        .andExpect(jsonPath("$.localAccounts.passwordResetEnabled").value(true));

    // the LOCAL row is what this endpoint reads itself, for the management flag
    local.disable();
    mockMvc
        .perform(get("/api/v1/auth/config"))
        .andExpect(jsonPath("$.localAccounts.enabled").value(false));
  }
}
