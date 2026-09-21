package io.opaa.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.DirectorySyncOutcome;
import io.opaa.auth.AdminTestSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.group.sync.DirectorySyncService;
import io.opaa.group.sync.PendingPlanView;
import io.opaa.group.sync.SyncReport;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * {@link DirectorySyncController} in isolation (#1816): the {@code SYSTEM_ADMIN} bar on every
 * operation, the mandatory reason of a decision about a pending plan, and the pass-through of the
 * service's own 404s and 409s.
 */
@WebMvcTest(DirectorySyncController.class)
@ActiveProfiles("dev")
@Import(AdminTestSecurityConfig.class)
class DirectorySyncControllerTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String TEST_SUBJECT = "test-subject";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private DirectorySyncService directorySyncService;
  @MockitoBean private UserService userService;

  private final UUID actingAdminId = UUID.randomUUID();
  private final UUID actingAdminOrganizationId = UUID.randomUUID();
  private final UUID providerId = UUID.randomUUID();
  private final UUID planId = UUID.randomUUID();

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

  @Test
  void aRegularUserIsRejectedOnEveryOperation() throws Exception {
    String reason = "{\"reason\":\"Anlass\"}";
    mockMvc
        .perform(get("/api/v1/admin/directory-sync/status").with(asRegularUser()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/api/v1/admin/oidc-providers/" + providerId + "/directory-sync/dry-run")
                .with(asRegularUser()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/api/v1/admin/oidc-providers/" + providerId + "/directory-sync/run")
                .with(asRegularUser()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            get("/api/v1/admin/oidc-providers/" + providerId + "/directory-sync/pending-plan")
                .with(asRegularUser()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post(confirmPath())
                .with(asRegularUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(reason))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post(discardPath())
                .with(asRegularUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(reason))
        .andExpect(status().isForbidden());
  }

  @Test
  void aRunThatIsRefusedByTheServiceAnswersWithThatConflict() throws Exception {
    when(directorySyncService.run(any(), eq(providerId)))
        .thenThrow(
            new ConflictException(
                "Für diesen Anbieter ist der Verzeichnisabgleich nicht eingeschaltet.",
                DirectorySyncService.NOT_ENABLED_CODE));

    mockMvc
        .perform(
            post("/api/v1/admin/oidc-providers/" + providerId + "/directory-sync/run")
                .with(asAdmin()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(DirectorySyncService.NOT_ENABLED_CODE));
  }

  @Test
  void aProviderWithoutAPendingPlanAnswersNotFound() throws Exception {
    when(directorySyncService.getPendingPlan(any(), eq(providerId))).thenReturn(Optional.empty());

    mockMvc
        .perform(
            get("/api/v1/admin/oidc-providers/" + providerId + "/directory-sync/pending-plan")
                .with(asAdmin()))
        .andExpect(status().isNotFound());
  }

  @Test
  void thePendingPlanCarriesItsReport() throws Exception {
    when(directorySyncService.getPendingPlan(any(), eq(providerId)))
        .thenReturn(
            Optional.of(
                new PendingPlanView(
                    planId,
                    providerId,
                    Instant.parse("2026-09-20T04:00:00Z"),
                    0.67,
                    12,
                    3,
                    report(DirectorySyncOutcome.PENDING_CONFIRMATION))));

    mockMvc
        .perform(
            get("/api/v1/admin/oidc-providers/" + providerId + "/directory-sync/pending-plan")
                .with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(planId.toString()))
        .andExpect(jsonPath("$.report.outcome").value("PENDING_CONFIRMATION"));
  }

  /** "mit der bestätigenden Person und ihrem Anlass" - an empty reason is no reason. */
  @Test
  void aDecisionWithoutAReasonIsRefusedAsAValidationError() throws Exception {
    mockMvc
        .perform(
            post(confirmPath())
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"\"}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post(discardPath())
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aConfirmationPassesTheCallersIdentityAndReasonToTheService() throws Exception {
    when(directorySyncService.confirmPlan(any(), eq(providerId), eq(planId), any(), any()))
        .thenReturn(report(DirectorySyncOutcome.APPLIED));

    mockMvc
        .perform(
            post(confirmPath())
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Reorganisation\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("APPLIED"));

    verify(directorySyncService)
        .confirmPlan(
            actingAdminOrganizationId, providerId, planId, actingAdminId, "Reorganisation");
  }

  @Test
  void aSupersededPlanAnswersNotFoundAndAChangedOneAnswersConflict() throws Exception {
    when(directorySyncService.confirmPlan(any(), eq(providerId), eq(planId), any(), any()))
        .thenThrow(new NotFoundException("Dieser Plan liegt nicht mehr zur Entscheidung vor."));

    mockMvc
        .perform(
            post(confirmPath())
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Anlass\"}"))
        .andExpect(status().isNotFound());

    org.mockito.Mockito.reset(directorySyncService);
    when(directorySyncService.confirmPlan(any(), eq(providerId), eq(planId), any(), any()))
        .thenThrow(
            new ConflictException(
                "Das Verzeichnis hat sich seit der Vorlage geändert.",
                DirectorySyncService.PLAN_CHANGED_CODE));

    mockMvc
        .perform(
            post(confirmPath())
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Anlass\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(DirectorySyncService.PLAN_CHANGED_CODE));
  }

  @Test
  void discardingAnsweredWithoutContent() throws Exception {
    mockMvc
        .perform(
            post(discardPath())
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Verzeichnis war fehlerhaft.\"}"))
        .andExpect(status().isNoContent());

    verify(directorySyncService)
        .discardPlan(
            actingAdminOrganizationId,
            providerId,
            planId,
            actingAdminId,
            "Verzeichnis war fehlerhaft.");
  }

  private String confirmPath() {
    return "/api/v1/admin/oidc-providers/"
        + providerId
        + "/directory-sync/pending-plan/"
        + planId
        + "/confirm";
  }

  private String discardPath() {
    return "/api/v1/admin/oidc-providers/"
        + providerId
        + "/directory-sync/pending-plan/"
        + planId
        + "/discard";
  }

  private SyncReport report(DirectorySyncOutcome outcome) {
    return new SyncReport(
        outcome,
        Instant.parse("2026-09-20T04:00:00Z"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        0,
        0,
        0,
        0.67,
        0.3,
        "Bericht");
  }
}
