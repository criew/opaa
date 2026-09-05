package io.opaa.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.AdminTestSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderConnectionTester;
import io.opaa.auth.oidc.OidcProviderDraft;
import io.opaa.auth.oidc.OidcProviderRegistry;
import io.opaa.auth.oidc.OidcProviderService;
import io.opaa.common.ConflictException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * {@link OidcProviderController} in isolation (#1329): the {@code SYSTEM_ADMIN} bar on every
 * operation, the request-to-draft mapping including the Keycloak defaults for omitted claim fields,
 * and the pass-through of the service's 409s.
 */
@WebMvcTest(OidcProviderController.class)
@ActiveProfiles("dev")
@Import(AdminTestSecurityConfig.class)
class OidcProviderControllerTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String TEST_SUBJECT = "test-subject";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private OidcProviderService providerService;
  @MockitoBean private OidcProviderConnectionTester connectionTester;
  @MockitoBean private OidcProviderRegistry registry;
  @MockitoBean private UserService userService;

  private final UUID actingAdminId = UUID.randomUUID();
  private final UUID actingAdminOrganizationId = UUID.randomUUID();

  private RequestPostProcessor asAdmin() {
    return jwt()
        .jwt(builder -> builder.subject(TEST_SUBJECT).claim("iss", TEST_ISSUER))
        .authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"));
  }

  private RequestPostProcessor asRegularUser() {
    return jwt()
        .jwt(builder -> builder.subject(TEST_SUBJECT).claim("iss", TEST_ISSUER))
        .authorities(new SimpleGrantedAuthority("ROLE_USER"));
  }

  @BeforeEach
  void setUp() {
    User actingAdmin = new User(TEST_SUBJECT, TEST_ISSUER, "admin@example.com", "Admin");
    actingAdmin.setOrganizationId(actingAdminOrganizationId);
    setId(actingAdmin, actingAdminId);
    when(userService.provisionFromToken(
            org.mockito.ArgumentMatchers.argThat(
                token -> token != null && TEST_SUBJECT.equals(token.getSubject()))))
        .thenReturn(actingAdmin);
    when(registry.healthOf(any())).thenReturn(new OidcProviderRegistry.Health(true, null));
  }

  private void setId(User user, UUID id) {
    try {
      var field = User.class.getDeclaredField("id");
      field.setAccessible(true);
      field.set(user, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static OidcProvider provider(String name, String issuer) {
    return new OidcProvider(
        name, issuer, "opaa-frontend", null, OidcClaimMapping.keycloakDefaults());
  }

  @Test
  void aRegularUserIsRejectedOnEveryOperation() throws Exception {
    UUID id = UUID.randomUUID();
    String body =
        "{\"displayName\":\"X\",\"issuerUri\":\"https://idp.example\",\"clientId\":\"c\"}";
    mockMvc
        .perform(get("/api/v1/admin/oidc-providers").with(asRegularUser()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/api/v1/admin/oidc-providers")
                .with(asRegularUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            put("/api/v1/admin/oidc-providers/" + id)
                .with(asRegularUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(delete("/api/v1/admin/oidc-providers/" + id).with(asRegularUser()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(post("/api/v1/admin/oidc-providers/" + id + "/enable").with(asRegularUser()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(post("/api/v1/admin/oidc-providers/" + id + "/default").with(asRegularUser()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/api/v1/admin/oidc-providers/test")
                .with(asRegularUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"issuerUri\":\"https://idp.example\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void listsProvidersWithoutAnySecretField() throws Exception {
    OidcProvider provider = provider("Verzeichnisdienst", "https://idp.example/realms/a");
    provider.markDefault();
    when(providerService.listProviders()).thenReturn(List.of(provider));

    mockMvc
        .perform(get("/api/v1/admin/oidc-providers").with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].displayName").value("Verzeichnisdienst"))
        .andExpect(jsonPath("$[0].issuerUri").value("https://idp.example/realms/a"))
        .andExpect(jsonPath("$[0].isDefault").value(true))
        .andExpect(jsonPath("$[0].enabled").value(true))
        .andExpect(jsonPath("$[0].claimMapping.emailClaim").value("email"))
        .andExpect(jsonPath("$[0].registryState").value("READY"))
        .andExpect(jsonPath("$[0].clientSecret").doesNotExist());
  }

  @Test
  void aProviderWhoseDecoderCouldNotBeBuiltIsReportedAsUnavailableWithTheReason() throws Exception {
    OidcProvider provider = provider("Partner", "https://idp.example/realms/b");
    when(providerService.listProviders()).thenReturn(List.of(provider));
    when(registry.healthOf(provider.getId()))
        .thenReturn(new OidcProviderRegistry.Health(false, "Discovery-Dokument: nicht erreichbar"));

    mockMvc
        .perform(get("/api/v1/admin/oidc-providers").with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].registryState").value("UNAVAILABLE"))
        .andExpect(jsonPath("$[0].registryMessage").value("Discovery-Dokument: nicht erreichbar"));
  }

  @Test
  void aDisabledProviderIsReportedAsDisabledNotUnavailable() throws Exception {
    OidcProvider provider = provider("Alt", "https://idp.example/realms/alt");
    provider.disable();
    when(providerService.listProviders()).thenReturn(List.of(provider));
    when(registry.healthOf(provider.getId()))
        .thenReturn(new OidcProviderRegistry.Health(false, null));

    mockMvc
        .perform(get("/api/v1/admin/oidc-providers").with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].registryState").value("DISABLED"));
  }

  @Test
  void creatingAProviderFillsOmittedClaimFieldsWithTheKeycloakDefaults() throws Exception {
    OidcProvider created = provider("Partner", "https://idp.example/realms/b");
    when(providerService.createProvider(
            eq(actingAdminOrganizationId), eq(actingAdminId), any(OidcProviderDraft.class)))
        .thenReturn(created);

    mockMvc
        .perform(
            post("/api/v1/admin/oidc-providers")
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"displayName\":\"Partner\",\"issuerUri\":\"https://idp.example/realms/b\","
                        + "\"clientId\":\"opaa-frontend\",\"claimMapping\":{\"rolesClaim\":"
                        + "\"realm_access.roles\",\"systemAdminRole\":\"opaa-admin\"}}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.displayName").value("Partner"));

    ArgumentCaptor<OidcProviderDraft> draft = ArgumentCaptor.forClass(OidcProviderDraft.class);
    verify(providerService)
        .createProvider(eq(actingAdminOrganizationId), eq(actingAdminId), draft.capture());
    org.assertj.core.api.Assertions.assertThat(draft.getValue().claimMapping().emailClaim())
        .isEqualTo("email");
    org.assertj.core.api.Assertions.assertThat(draft.getValue().claimMapping().rolesClaim())
        .isEqualTo("realm_access.roles");
    org.assertj.core.api.Assertions.assertThat(draft.getValue().claimMapping().systemAdminRole())
        .isEqualTo("opaa-admin");
    org.assertj.core.api.Assertions.assertThat(draft.getValue().jwkSetUri()).isNull();
  }

  @Test
  void aMissingIssuerIsA400BeforeTheServiceIsAsked() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/oidc-providers")
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Partner\",\"clientId\":\"opaa-frontend\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void theServicesConflictsPassThroughAs409() throws Exception {
    UUID id = UUID.randomUUID();
    when(providerService.setEnabled(actingAdminOrganizationId, actingAdminId, id, false))
        .thenThrow(new ConflictException("Der Standardanbieter kann nicht deaktiviert werden."));

    mockMvc
        .perform(post("/api/v1/admin/oidc-providers/" + id + "/disable").with(asAdmin()))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.error").value("Der Standardanbieter kann nicht deaktiviert werden."));
  }

  @Test
  void reorderingHandsTheIdsToTheServiceInRequestOrder() throws Exception {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    when(providerService.reorder(
            eq(actingAdminOrganizationId), eq(actingAdminId), eq(List.of(second, first))))
        .thenReturn(List.of());

    mockMvc
        .perform(
            put("/api/v1/admin/oidc-providers/order")
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"providerIds\":[\"" + second + "\",\"" + first + "\"]}"))
        .andExpect(status().isOk());
  }

  @Test
  void theConnectionTestReportsTheTestersOutcome() throws Exception {
    when(connectionTester.test("https://idp.example/realms/a", null))
        .thenReturn(
            new OidcProviderConnectionTester.TestOutcome(
                false, "Der Issuer ist nicht erreichbar."));

    mockMvc
        .perform(
            post("/api/v1/admin/oidc-providers/test")
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"issuerUri\":\"https://idp.example/realms/a\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.message").value("Der Issuer ist nicht erreichbar."));
  }
}
