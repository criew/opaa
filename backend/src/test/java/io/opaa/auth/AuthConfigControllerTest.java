package io.opaa.auth;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /api/v1/auth/config} (#1332, ADR-0025 Entscheidung 5): the mode, and in the {@code
 * oidc} mode the ready providers in sign-in page order with exactly the fields the sign-in page
 * shows - nothing else about a provider leaves the backend unauthenticated.
 */
@WebMvcTest(AuthConfigController.class)
@Import(TestSecurityConfig.class)
class AuthConfigControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private AuthProperties authProperties;
  @MockitoBean private OidcProviderRegistry providerRegistry;

  // TestSecurityConfig's UserProvisioningFilter needs a UserService bean even though this
  // unauthenticated endpoint never calls it.
  @MockitoBean private UserService userService;

  @Test
  void inTheDevModeThereAreNoProviders() throws Exception {
    when(authProperties.mode()).thenReturn("dev");

    mockMvc
        .perform(get("/api/v1/auth/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("dev"))
        .andExpect(jsonPath("$.providers").isEmpty());
  }

  @Test
  void inTheOidcModeTheReadyProvidersAreListedInSignInOrderWithTheirPublicFields()
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
    when(providerRegistry.enabledProviders()).thenReturn(List.of(standard, partner));

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
        .andExpect(jsonPath("$.providers[0].rolesClaim").doesNotExist());
  }
}
