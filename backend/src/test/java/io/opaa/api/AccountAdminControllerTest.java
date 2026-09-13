package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.LocalAccountActivity;
import io.opaa.api.types.LocalAccountState;
import io.opaa.api.types.ProviderType;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.AdminTestSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.auth.account.AccountAdminService;
import io.opaa.auth.account.AccountOverview;
import io.opaa.auth.account.AccountPage;
import io.opaa.auth.account.AccountQuery;
import io.opaa.auth.local.LocalCredentials;
import io.opaa.auth.local.LocalUserOverview;
import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(AccountAdminController.class)
@ActiveProfiles("dev")
@Import(AdminTestSecurityConfig.class)
class AccountAdminControllerTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String ADMIN_SUBJECT = "admin-subject";
  private static final String USER_SUBJECT = "user-subject";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private AccountAdminService adminService;
  @MockitoBean private UserService userService;

  private final UUID organizationId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    User actingAdmin = new User(ADMIN_SUBJECT, TEST_ISSUER, "admin@example.com", "Admin");
    actingAdmin.setSystemRole(SystemRole.SYSTEM_ADMIN);
    actingAdmin.setOrganizationId(organizationId);
    setId(actingAdmin, UUID.randomUUID());
    when(userService.provisionFromToken(
            org.mockito.ArgumentMatchers.argThat(
                token -> token != null && ADMIN_SUBJECT.equals(token.getSubject()))))
        .thenReturn(actingAdmin);

    User regular = new User(USER_SUBJECT, TEST_ISSUER, "user@example.com", "User");
    regular.setOrganizationId(organizationId);
    setId(regular, UUID.randomUUID());
    when(userService.provisionFromToken(
            org.mockito.ArgumentMatchers.argThat(
                token -> token != null && USER_SUBJECT.equals(token.getSubject()))))
        .thenReturn(regular);
  }

  @Test
  void aRegularUserIsRejected() throws Exception {
    mockMvc.perform(get("/api/v1/admin/accounts").with(asUser())).andExpect(status().isForbidden());
  }

  @Test
  void theListTranslatesTheParametersAndRendersBothKindsOfAccount() throws Exception {
    OidcProvider provider =
        new OidcProvider(
            "Verzeichnisdienst",
            "http://idp.example/realms/opaa",
            "opaa-frontend",
            null,
            OidcClaimMapping.keycloakDefaults());
    provider.enable();
    User mariaUser =
        new User("maria", "http://idp.example/realms/opaa", "maria@stadt.example", "Maria Weber");
    setId(mariaUser, UUID.randomUUID());
    AccountOverview maria = new AccountOverview(mariaUser, provider, null, false);
    UUID erikaId = UUID.randomUUID();
    User erikaUser = User.localAccount("erika@stadt.example", "Erika Muster");
    setId(erikaUser, erikaId);
    LocalCredentials row = new LocalCredentials(erikaId, "Vertretung", Instant.now());
    AccountOverview erika =
        new AccountOverview(
            erikaUser,
            null,
            new LocalUserOverview(
                erikaUser, row, LocalAccountState.INVITED, LocalAccountActivity.NEVER),
            false);
    UUID providerId = provider.getId();
    when(adminService.list(eq(organizationId), any()))
        .thenReturn(new AccountPage(List.of(erika, maria), 2, 1, 10));

    mockMvc
        .perform(
            get("/api/v1/admin/accounts")
                .with(asAdmin())
                .param("query", "stadt")
                .param("providerType", "OIDC")
                .param("providerId", providerId.toString())
                .param("role", "USER")
                .param("status", "INVITED")
                .param("withoutExpiry", "true")
                .param("inactive", "true")
                .param("sort", "createdAt")
                .param("direction", "desc")
                .param("page", "1")
                .param("size", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].providerType").value("LOCAL"))
        .andExpect(jsonPath("$.items[0].issuer").value("urn:opaa:local"))
        .andExpect(jsonPath("$.items[0].local.status").value("INVITED"))
        .andExpect(jsonPath("$.items[0].local.activity").value("NEVER"))
        .andExpect(jsonPath("$.items[0].provider").doesNotExist())
        .andExpect(jsonPath("$.items[1].providerType").value("OIDC"))
        .andExpect(jsonPath("$.items[1].provider.displayName").value("Verzeichnisdienst"))
        .andExpect(jsonPath("$.items[1].provider.enabled").value(true))
        .andExpect(jsonPath("$.items[1].roleManagedByProvider").value(false))
        .andExpect(jsonPath("$.items[1].local").doesNotExist())
        .andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.page").value(1));

    ArgumentCaptor<AccountQuery> query = ArgumentCaptor.forClass(AccountQuery.class);
    verify(adminService).list(eq(organizationId), query.capture());
    assertThat(query.getValue())
        .isEqualTo(
            new AccountQuery(
                "stadt",
                ProviderType.OIDC,
                providerId,
                SystemRole.USER,
                LocalAccountState.INVITED,
                true,
                true,
                AccountQuery.Sort.CREATED_AT,
                true,
                1,
                10));
  }

  @Test
  void theBoundsOfTheLocalListApply() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/accounts").with(asAdmin()).param("size", "51"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/v1/admin/accounts").with(asAdmin()).param("sort", "activity"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/v1/admin/accounts").with(asAdmin()).param("sort", "lastLoginAt"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/v1/admin/accounts").with(asAdmin()).param("direction", "sideways"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/v1/admin/accounts").with(asAdmin()).param("page", "100000000"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/v1/admin/accounts").with(asAdmin()).param("query", "x".repeat(321)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void theThreeColumnsOfTheAccountListAreSortableToo() throws Exception {
    when(adminService.list(eq(organizationId), any()))
        .thenReturn(new AccountPage(List.of(), 0, 0, 25));

    for (var entry :
        java.util.Map.of(
                "origin", AccountQuery.Sort.ORIGIN,
                "role", AccountQuery.Sort.ROLE,
                "status", AccountQuery.Sort.STATUS)
            .entrySet()) {
      mockMvc
          .perform(get("/api/v1/admin/accounts").with(asAdmin()).param("sort", entry.getKey()))
          .andExpect(status().isOk());
      ArgumentCaptor<AccountQuery> query = ArgumentCaptor.forClass(AccountQuery.class);
      verify(adminService, org.mockito.Mockito.atLeastOnce())
          .list(eq(organizationId), query.capture());
      assertThat(query.getValue().sort()).isEqualTo(entry.getValue());
    }
  }

  private RequestPostProcessor asAdmin() {
    return jwt()
        .jwt(builder -> builder.subject(ADMIN_SUBJECT).claim("iss", TEST_ISSUER))
        .authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"));
  }

  private RequestPostProcessor asUser() {
    return jwt()
        .jwt(builder -> builder.subject(USER_SUBJECT).claim("iss", TEST_ISSUER))
        .authorities(new SimpleGrantedAuthority("ROLE_USER"));
  }

  private static void setId(User user, UUID id) {
    try {
      var field = User.class.getDeclaredField("id");
      field.setAccessible(true);
      field.set(user, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
