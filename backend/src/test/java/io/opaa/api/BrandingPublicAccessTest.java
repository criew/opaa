package io.opaa.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.OidcSecurityConfig;
import io.opaa.auth.UserService;
import io.opaa.branding.BrandingDefaults;
import io.opaa.branding.BrandingImageKind;
import io.opaa.branding.BrandingImageValidator;
import io.opaa.branding.BrandingSettingsService;
import io.opaa.branding.EffectiveBranding;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * #583/#1910: the sign-in page renders before there is a session and still has to carry the
 * operator's product name, claim, logo and background - so {@code GET /api/v1/branding} and the
 * three image paths under it are reachable without authentication, and nothing else under {@code
 * /api/v1} is.
 *
 * <p>Runs against the real {@link OidcSecurityConfig} chain rather than the {@code dev} one,
 * because {@code dev} cannot express the question: {@code DevAuthFilter} authenticates every
 * request as the configured default user before authorization is ever consulted, so a request
 * without credentials does not exist there. Under {@code oidc}, a request with no {@code Bearer}
 * token is genuinely anonymous - which is exactly the sign-in page's situation.
 *
 * <p>A {@code @WebMvcTest} slice, deliberately: this asserts a property of the filter chain's
 * authorization rules, which needs no database, no Liquibase and no application context beyond the
 * web layer. {@link BrandingControllerIntegrationTest} carries everything that does.
 */
@WebMvcTest(controllers = {BrandingController.class, SystemBrandingController.class})
@Import({OidcSecurityConfig.class, BrandingPublicAccessTest.CorsStub.class})
@ActiveProfiles("oidc")
class BrandingPublicAccessTest {

  @TestConfiguration
  static class CorsStub {
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
      UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
      source.registerCorsConfiguration("/api/**", new CorsConfiguration());
      return source;
    }
  }

  @Autowired private MockMvc mockMvc;

  @MockitoBean private BrandingSettingsService brandingSettingsService;
  @MockitoBean private BrandingImageValidator imageValidator;
  @MockitoBean private UserService userService;

  @MockitoBean
  private AuthenticationManagerResolver<HttpServletRequest> oidcAuthenticationManagerResolver;

  @Test
  void brandingIsReadableWithoutAnyCredentials() throws Exception {
    when(brandingSettingsService.currentBranding())
        .thenReturn(
            new EffectiveBranding(
                BrandingDefaults.PRODUCT_NAME,
                BrandingDefaults.CLAIM,
                BrandingDefaults.PRIMARY_COLOR,
                BrandingDefaults.COLOR_SCHEME,
                Map.of()));

    mockMvc.perform(get("/api/v1/branding")).andExpect(status().isOk());
  }

  /**
   * Each image path is permitted separately from the settings path, so each needs its own proof -
   * 404 here means the request reached the controller and found nothing configured, which is
   * precisely what "not rejected by authorization" looks like for these endpoints.
   */
  @Test
  void everyImagePathIsReadableWithoutAnyCredentials() throws Exception {
    for (BrandingImageKind kind : BrandingImageKind.values()) {
      when(brandingSettingsService.currentImage(kind)).thenReturn(Optional.empty());
    }

    for (String path : new String[] {"logo", "login-logo", "login-background"}) {
      mockMvc.perform(get("/api/v1/branding/" + path)).andExpect(status().isNotFound());
    }
  }

  /** Uploading an image stays behind authentication, for every kind. */
  @Test
  void changingAnImageIsStillRejectedWithoutCredentials() throws Exception {
    for (String path : new String[] {"logo", "login-logo", "login-background"}) {
      mockMvc
          .perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                  "/api/v1/system/branding/" + path))
          .andExpect(status().isUnauthorized());
    }
  }

  /**
   * The other half of the decision: opening branding must not have opened anything else. Writing
   * branding stays behind authentication (and, once authenticated, behind SYSTEM_ADMIN - proven in
   * {@link BrandingControllerIntegrationTest}).
   */
  @Test
  void changingBrandingIsStillRejectedWithoutCredentials() throws Exception {
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/v1/system/branding")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"productName\":\"Fremd\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void anUnknownApiPathIsStillRejectedWithoutCredentials() throws Exception {
    mockMvc.perform(get("/api/v1/spaces")).andExpect(status().isUnauthorized());
    // no bearer token on the request: the resolver is never even consulted
    org.mockito.Mockito.verifyNoInteractions(oidcAuthenticationManagerResolver);
  }
}
