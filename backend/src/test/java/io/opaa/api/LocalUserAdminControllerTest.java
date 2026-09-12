package io.opaa.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.LocalAccountActivity;
import io.opaa.api.types.LocalAccountState;
import io.opaa.api.types.MailDeliveryPath;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.AdminTestSecurityConfig;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.auth.local.LinkDelivery;
import io.opaa.auth.local.LocalAuthSettings;
import io.opaa.auth.local.LocalAuthSettingsService;
import io.opaa.auth.local.LocalCredentials;
import io.opaa.auth.local.LocalUserAdminService;
import io.opaa.auth.local.LocalUserCreated;
import io.opaa.auth.local.LocalUserCreation;
import io.opaa.auth.local.LocalUserOverview;
import io.opaa.auth.local.LocalUserPage;
import io.opaa.auth.local.LocalUserQuery;
import io.opaa.auth.local.LocalUserSummary;
import io.opaa.auth.local.LocalUserUpdate;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import java.time.Instant;
import java.time.LocalDate;
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
 * {@link LocalUserAdminController} and {@link LocalAuthSettingsController} in isolation (#1537):
 * the {@code SYSTEM_ADMIN} bar on every operation, the translation of query and request DTOs into
 * the domain parameters, the response shapes, and the 404/409 mapping of the domain exceptions.
 * Everything behind the two services is proved by the integration tests.
 */
@WebMvcTest({LocalUserAdminController.class, LocalAuthSettingsController.class})
@ActiveProfiles("dev")
@Import(AdminTestSecurityConfig.class)
class LocalUserAdminControllerTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String ADMIN_SUBJECT = "admin-subject";
  private static final String USER_SUBJECT = "user-subject";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private LocalUserAdminService adminService;
  @MockitoBean private LocalAuthSettingsService settingsService;
  @MockitoBean private UserService userService;

  private final UUID adminId = UUID.randomUUID();
  private final UUID organizationId = UUID.randomUUID();
  private User actingAdmin;
  private CurrentUser caller;

  @BeforeEach
  void setUp() {
    actingAdmin = new User(ADMIN_SUBJECT, TEST_ISSUER, "admin@example.com", "Admin");
    actingAdmin.setSystemRole(SystemRole.SYSTEM_ADMIN);
    actingAdmin.setOrganizationId(organizationId);
    setId(actingAdmin, adminId);
    caller =
        CurrentUser.of(
            adminId, organizationId, SystemRole.SYSTEM_ADMIN, "Admin", "admin@example.com");
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
  void aRegularUserIsRejectedOnEveryOperation() throws Exception {
    UUID id = UUID.randomUUID();
    mockMvc
        .perform(get("/api/v1/admin/local-users").with(asUser()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(get("/api/v1/admin/local-users/summary").with(asUser()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/api/v1/admin/local-users")
                .with(asUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"x@stadt.example\",\"displayName\":\"X\",\"mode\":\"INVITE\",\"createdReason\":\"x\"}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(get("/api/v1/admin/local-users/" + id).with(asUser()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            patch("/api/v1/admin/local-users/" + id)
                .with(asUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(delete("/api/v1/admin/local-users/" + id).with(asUser()))
        .andExpect(status().isForbidden());
    for (String action : List.of("lock", "unlock", "password-reset", "password")) {
      mockMvc
          .perform(post("/api/v1/admin/local-users/" + id + "/" + action).with(asUser()))
          .andExpect(status().isForbidden());
    }
    mockMvc
        .perform(get("/api/v1/admin/local-auth-settings").with(asUser()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            put("/api/v1/admin/local-auth-settings")
                .with(asUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"enabled\":false,\"selfRegistrationEnabled\":false,\"selfRegistrationAllowedDomains\":[],\"passwordResetEnabled\":false,\"passwordMinLength\":12,\"invitationTokenTtlHours\":72,\"resetTokenTtlMinutes\":30,\"defaultExpiryDays\":90,\"inactiveDays\":90}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void theListTranslatesTheQueryParametersAndCapsThePage() throws Exception {
    LocalUserOverview overview = overview(UUID.randomUUID(), "erika@stadt.example");
    when(adminService.list(eq(organizationId), any()))
        .thenReturn(new LocalUserPage(List.of(overview), 1, 0, 10));

    mockMvc
        .perform(
            get("/api/v1/admin/local-users")
                .with(asAdmin())
                .param("query", "erika")
                .param("status", "LOCKED")
                .param("role", "USER")
                .param("withoutExpiry", "true")
                .param("inactive", "true")
                .param("sort", "expiresAt")
                .param("direction", "desc")
                .param("page", "2")
                .param("size", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].email").value("erika@stadt.example"))
        .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
        .andExpect(jsonPath("$.items[0].activity").value("NEVER"))
        .andExpect(jsonPath("$.total").value(1));
    ArgumentCaptor<LocalUserQuery> query = ArgumentCaptor.forClass(LocalUserQuery.class);
    verify(adminService).list(eq(organizationId), query.capture());
    org.assertj.core.api.Assertions.assertThat(query.getValue())
        .isEqualTo(
            new LocalUserQuery(
                "erika",
                LocalAccountState.LOCKED,
                SystemRole.USER,
                true,
                true,
                LocalUserQuery.Sort.EXPIRES_AT,
                true,
                2,
                10));

    mockMvc
        .perform(get("/api/v1/admin/local-users").with(asAdmin()).param("size", "51"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/v1/admin/local-users").with(asAdmin()).param("sort", "lastLoginAt"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/v1/admin/local-users").with(asAdmin()).param("direction", "sideways"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/v1/admin/local-users").with(asAdmin()).param("page", "100000000"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/v1/admin/local-users").with(asAdmin()).param("query", "x".repeat(321)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void theSummaryCarriesTheCountsAndTheReviewHint() throws Exception {
    when(adminService.summary(organizationId))
        .thenReturn(new LocalUserSummary(7, 3, 1, 2, LocalDate.of(2026, 10, 1)));

    mockMvc
        .perform(get("/api/v1/admin/local-users/summary").with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(7))
        .andExpect(jsonPath("$.withoutExpiry").value(3))
        .andExpect(jsonPath("$.locked").value(1))
        .andExpect(jsonPath("$.invitedPending").value(2))
        .andExpect(
            jsonPath("$.lastReviewHint").value(org.hamcrest.Matchers.containsString("01.10.2026")));
  }

  @Test
  void creatingByInvitationReturns201WithTheDeliveryAndTheLinkOnlyWhenNotSent() throws Exception {
    UUID id = UUID.randomUUID();
    when(adminService.create(eq(caller), any()))
        .thenReturn(
            new LocalUserCreated(
                overview(id, "neu@stadt.example"),
                new LinkDelivery(MailDeliveryPath.LINK_DISPLAYED, "/konto/passwort?token=abc"),
                null));

    mockMvc
        .perform(
            post("/api/v1/admin/local-users")
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"neu@stadt.example\",\"displayName\":\"Neu\",\"mode\":\"INVITE\","
                        + "\"createdReason\":\"Projekt\",\"systemRole\":\"AUDITOR\","
                        + "\"expiresAt\":\"2027-01-01T00:00:00Z\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.user.id").value(id.toString()))
        .andExpect(jsonPath("$.mode").value("INVITE"))
        .andExpect(jsonPath("$.emailSent").value(false))
        .andExpect(jsonPath("$.deliveryPath").value("LINK_DISPLAYED"))
        .andExpect(jsonPath("$.setupUrl").value("/konto/passwort?token=abc"))
        .andExpect(jsonPath("$.initialPassword").doesNotExist());
    ArgumentCaptor<LocalUserCreation> creation = ArgumentCaptor.forClass(LocalUserCreation.class);
    verify(adminService).create(eq(caller), creation.capture());
    org.assertj.core.api.Assertions.assertThat(creation.getValue())
        .isEqualTo(
            new LocalUserCreation(
                "neu@stadt.example",
                "Neu",
                SystemRole.AUDITOR,
                Instant.parse("2027-01-01T00:00:00Z"),
                false,
                "Projekt",
                true));
  }

  @Test
  void creatingWithAnInitialPasswordReturnsItOnceAndNoDelivery() throws Exception {
    UUID id = UUID.randomUUID();
    when(adminService.create(any(), any()))
        .thenReturn(
            new LocalUserCreated(overview(id, "neu@stadt.example"), null, "Geheim-Passwort-2026"));

    mockMvc
        .perform(
            post("/api/v1/admin/local-users")
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"neu@stadt.example\",\"displayName\":\"Neu\",\"mode\":\"INITIAL_PASSWORD\","
                        + "\"createdReason\":\"Projekt\",\"noExpiry\":true}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.mode").value("INITIAL_PASSWORD"))
        .andExpect(jsonPath("$.emailSent").value(false))
        .andExpect(jsonPath("$.deliveryPath").doesNotExist())
        .andExpect(jsonPath("$.setupUrl").doesNotExist())
        .andExpect(jsonPath("$.initialPassword").value("Geheim-Passwort-2026"));
    ArgumentCaptor<LocalUserCreation> creation = ArgumentCaptor.forClass(LocalUserCreation.class);
    verify(adminService).create(any(), creation.capture());
    org.assertj.core.api.Assertions.assertThat(creation.getValue().noExpiry()).isTrue();
    org.assertj.core.api.Assertions.assertThat(creation.getValue().invite()).isFalse();
    org.assertj.core.api.Assertions.assertThat(creation.getValue().systemRole()).isNull();
  }

  @Test
  void aMissingReasonOrModeIsRejectedBeforeTheService() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/local-users")
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"neu@stadt.example\",\"displayName\":\"Neu\",\"mode\":\"INVITE\"}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/v1/admin/local-users")
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"neu@stadt.example\",\"displayName\":\"Neu\",\"createdReason\":\"x\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void patchPassesOnlyTheGivenFieldsAndMapsTheConflictCode() throws Exception {
    UUID id = UUID.randomUUID();
    when(adminService.update(eq(caller), eq(id), any()))
        .thenReturn(overview(id, "erika@stadt.example"));

    mockMvc
        .perform(
            patch("/api/v1/admin/local-users/" + id)
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Erika Neu\",\"noExpiry\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.toString()));
    verify(adminService)
        .update(
            eq(caller), eq(id), eq(new LocalUserUpdate(null, "Erika Neu", null, null, true, null)));

    when(adminService.update(any(), eq(id), any()))
        .thenThrow(new ConflictException("Letzter Systemverwalter", "LAST_LOGIN_CAPABLE_ADMIN"));
    mockMvc
        .perform(
            patch("/api/v1/admin/local-users/" + id)
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"systemRole\":\"USER\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("LAST_LOGIN_CAPABLE_ADMIN"));
  }

  @Test
  void lockUnlockResetGenerateAndDeleteDelegateWithTheCaller() throws Exception {
    UUID id = UUID.randomUUID();
    when(adminService.lock(caller, id, "Dienstende")).thenReturn(overview(id, "e@stadt.example"));
    when(adminService.unlock(caller, id)).thenReturn(overview(id, "e@stadt.example"));
    when(adminService.requestPasswordReset(caller, id))
        .thenReturn(new LinkDelivery(MailDeliveryPath.MAIL_SENT, null));
    when(adminService.generatePassword(caller, id)).thenReturn("Neues-Passwort-2026");

    mockMvc
        .perform(
            post("/api/v1/admin/local-users/" + id + "/lock")
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Dienstende\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.toString()));
    mockMvc
        .perform(post("/api/v1/admin/local-users/" + id + "/unlock").with(asAdmin()))
        .andExpect(status().isOk());
    mockMvc
        .perform(post("/api/v1/admin/local-users/" + id + "/password-reset").with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.emailSent").value(true))
        .andExpect(jsonPath("$.deliveryPath").value("MAIL_SENT"))
        .andExpect(jsonPath("$.setupUrl").doesNotExist());
    mockMvc
        .perform(post("/api/v1/admin/local-users/" + id + "/password").with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.password").value("Neues-Passwort-2026"));
    mockMvc
        .perform(delete("/api/v1/admin/local-users/" + id).with(asAdmin()))
        .andExpect(status().isNoContent());
    verify(adminService).delete(caller, id);

    // a lock without a body carries no reason
    when(adminService.lock(eq(caller), eq(id), isNull()))
        .thenReturn(overview(id, "e@stadt.example"));
    mockMvc
        .perform(post("/api/v1/admin/local-users/" + id + "/lock").with(asAdmin()))
        .andExpect(status().isOk());
    verify(adminService).lock(caller, id, null);
  }

  @Test
  void anUnknownAccountIs404AndAConflictCarriesItsCode() throws Exception {
    UUID id = UUID.randomUUID();
    when(adminService.get(organizationId, id))
        .thenThrow(new NotFoundException("Konto nicht gefunden"));
    when(adminService.lock(any(), eq(id), any()))
        .thenThrow(new ConflictException("Nicht sich selbst", "SELF_LOCKOUT"));

    mockMvc
        .perform(get("/api/v1/admin/local-users/" + id).with(asAdmin()))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(post("/api/v1/admin/local-users/" + id + "/lock").with(asAdmin()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SELF_LOCKOUT"));
  }

  @Test
  void theSettingsAreReadAndReplacedThroughTheService() throws Exception {
    LocalAuthSettingsService.View view =
        new LocalAuthSettingsService.View(LocalAuthSettings.Values.defaults(), true, false);
    when(settingsService.current()).thenReturn(view);
    when(settingsService.update(eq(caller), any()))
        .thenReturn(
            new LocalAuthSettingsService.Updated(
                new LocalAuthSettingsService.View(
                    LocalAuthSettings.Values.defaults(), false, false),
                3));

    mockMvc
        .perform(get("/api/v1/admin/local-auth-settings").with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.passwordMinLength").value(12))
        .andExpect(jsonPath("$.selfRegistrationAllowedDomains").isEmpty())
        .andExpect(jsonPath("$.publicBaseUrlConfigured").value(false))
        .andExpect(jsonPath("$.revokedSessions").doesNotExist());

    mockMvc
        .perform(
            put("/api/v1/admin/local-auth-settings")
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"enabled\":false,\"selfRegistrationEnabled\":true,"
                        + "\"selfRegistrationAllowedDomains\":[\"stadt.example\"],"
                        + "\"passwordResetEnabled\":true,\"passwordMinLength\":14,"
                        + "\"invitationTokenTtlHours\":48,\"resetTokenTtlMinutes\":15,"
                        + "\"defaultExpiryDays\":180,\"inactiveDays\":60}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.revokedSessions").value(3));
    verify(settingsService)
        .update(
            eq(caller),
            eq(
                new LocalAuthSettingsService.Update(
                    false, true, List.of("stadt.example"), true, 14, 48, 15, 180, 60)));
  }

  private static LocalUserOverview overview(UUID id, String email) {
    User user = User.localAccount(email, "Erika Muster");
    setId(user, id);
    LocalCredentials row =
        new LocalCredentials(id, "Projekt", Instant.parse("2026-09-11T08:00:00Z"));
    return new LocalUserOverview(user, row, LocalAccountState.ACTIVE, LocalAccountActivity.NEVER);
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
