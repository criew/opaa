package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.OrphanedOriginalSkipReason;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.AdminTestSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.common.NotFoundException;
import io.opaa.library.OrphanedLibrary;
import io.opaa.library.OrphanedLibraryReport;
import io.opaa.library.OrphanedOriginal;
import io.opaa.library.OrphanedOriginalCleanupService;
import io.opaa.library.OrphanedOriginalDeletion;
import io.opaa.library.OrphanedOriginalReport;
import io.opaa.library.UploadStoreUnavailableException;
import java.time.Instant;
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
 * {@link UploadStoreAdminController} in isolation, the cleanup service mocked: the {@code
 * SYSTEM_ADMIN} bar, the caller's own organization as the scope of both runs, and the audit rule of
 * the two steps - reporting leaves no event, every delete call leaves exactly one.
 */
@WebMvcTest(UploadStoreAdminController.class)
@ActiveProfiles("dev")
@Import(AdminTestSecurityConfig.class)
class UploadStoreAdminControllerTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String TEST_SUBJECT = "test-subject";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private OrphanedOriginalCleanupService cleanupService;
  @MockitoBean private AuditEventRecorder auditEventRecorder;
  @MockitoBean private UserService userService;

  private final UUID actingAdminId = UUID.randomUUID();
  private final UUID actingAdminOrganizationId = UUID.randomUUID();
  private final UUID libraryId = UUID.randomUUID();

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
  void bothStepsAsRegularUserReturn403() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/upload-store/orphan-originals/report")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"libraryId\":\"" + libraryId + "\"}")
                .with(asRegularUser()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/api/v1/admin/upload-store/orphan-originals/delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"libraryId\":\"" + libraryId + "\",\"locators\":[\"a\"]}")
                .with(asRegularUser()))
        .andExpect(status().isForbidden());

    verifyNoInteractions(cleanupService);
    verifyNoInteractions(auditEventRecorder);
  }

  @Test
  void theReportScopesToTheCallersOwnOrganizationAndLeavesNoAuditEvent() throws Exception {
    when(cleanupService.report(actingAdminOrganizationId, libraryId, 180))
        .thenReturn(
            new OrphanedOriginalReport(
                List.of(
                    new OrphanedOriginal(
                        "s3://bucket/uploads/x.pdf", Instant.parse("2026-09-01T10:00:00Z"), 4711L)),
                3,
                42,
                37,
                2,
                180));

    mockMvc
        .perform(
            post("/api/v1/admin/upload-store/orphan-originals/report")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"libraryId\":\"" + libraryId + "\",\"minimumAgeMinutes\":180}")
                .with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orphans[0].locator").value("s3://bucket/uploads/x.pdf"))
        .andExpect(jsonPath("$.orphans[0].size").value(4711))
        .andExpect(jsonPath("$.orphanCount").value(3))
        .andExpect(jsonPath("$.scannedCount").value(42))
        .andExpect(jsonPath("$.referencedCount").value(37))
        .andExpect(jsonPath("$.withinGracePeriodCount").value(2))
        .andExpect(jsonPath("$.minimumAgeMinutes").value(180))
        .andExpect(jsonPath("$.truncated").value(true));

    verify(cleanupService).report(actingAdminOrganizationId, libraryId, 180);
    verifyNoInteractions(auditEventRecorder);
  }

  @Test
  void aRequestWithoutALibraryIsRejectedBeforeAnythingIsRecorded() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/upload-store/orphan-originals/delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"locators\":[\"a\"]}")
                .with(asAdmin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("libraryId ist erforderlich"));

    verifyNoInteractions(cleanupService);
    verifyNoInteractions(auditEventRecorder);
  }

  @Test
  void aStoreThatCannotBeReachedAnswers503() throws Exception {
    when(cleanupService.report(actingAdminOrganizationId, libraryId, null))
        .thenThrow(new UploadStoreUnavailableException());

    mockMvc
        .perform(
            post("/api/v1/admin/upload-store/orphan-originals/report")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"libraryId\":\"" + libraryId + "\"}")
                .with(asAdmin()))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void deletingRecordsOneAuditEventNamingEveryLocatorThatWent() throws Exception {
    when(cleanupService.delete(
            actingAdminOrganizationId, libraryId, List.of("locator-1", "locator-2")))
        .thenReturn(
            new OrphanedOriginalDeletion(
                List.of("locator-1"),
                List.of(
                    new OrphanedOriginalDeletion.Skipped(
                        "locator-2", OrphanedOriginalSkipReason.REFERENCED))));

    mockMvc
        .perform(
            post("/api/v1/admin/upload-store/orphan-originals/delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"libraryId\":\""
                        + libraryId
                        + "\",\"locators\":[\"locator-1\",\"locator-2\"]}")
                .with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deleted[0]").value("locator-1"))
        .andExpect(jsonPath("$.skipped[0].locator").value("locator-2"))
        .andExpect(jsonPath("$.skipped[0].reason").value("REFERENCED"));

    ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventRecorder).recordUserAction(auditCaptor.capture());
    AuditEvent event = auditCaptor.getValue();
    assertThat(event.eventType()).isEqualTo(AuditEventType.UPLOAD_ORPHAN_ORIGINALS_DELETED);
    assertThat(event.objectType()).isEqualTo(AuditObjectType.KNOWLEDGE_LIBRARY);
    assertThat(event.objectId()).isEqualTo(libraryId);
    assertThat(event.organizationId()).isEqualTo(actingAdminOrganizationId);
    assertThat(event.actorUserId()).isEqualTo(actingAdminId);
    assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
    assertThat(event.after())
        .containsEntry("requestedCount", 2)
        .containsEntry("deletedCount", 1)
        .containsEntry("skippedCount", 1)
        .containsEntry("deleted", List.of("locator-1"));
  }

  @Test
  void aRejectedDeleteIsRecordedAsFailureAndStillAnswersItsOwnStatus() throws Exception {
    when(cleanupService.delete(actingAdminOrganizationId, libraryId, List.of("locator-1")))
        .thenThrow(new NotFoundException("Bibliothek nicht gefunden"));

    mockMvc
        .perform(
            post("/api/v1/admin/upload-store/orphan-originals/delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"libraryId\":\"" + libraryId + "\",\"locators\":[\"locator-1\"]}")
                .with(asAdmin()))
        .andExpect(status().isNotFound());

    ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventRecorder).recordUserAction(auditCaptor.capture());
    AuditEvent event = auditCaptor.getValue();
    assertThat(event.outcome()).isEqualTo(AuditOutcome.FAILURE);
    assertThat(event.reason()).isEqualTo("Bibliothek nicht gefunden");
    assertThat(event.after()).containsEntry("requestedCount", 1).doesNotContainKey("deleted");
  }

  @Test
  void bothStorageBoundStepsAsRegularUserReturn403() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/upload-store/orphan-libraries/report")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .with(asRegularUser()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/api/v1/admin/upload-store/orphan-libraries/delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"libraryId\":\"" + libraryId + "\",\"locators\":[\"a\"]}")
                .with(asRegularUser()))
        .andExpect(status().isForbidden());

    verifyNoInteractions(cleanupService);
    verifyNoInteractions(auditEventRecorder);
  }

  @Test
  void theLibraryReportTakesTheOrganizationFromTheCallerAndLeavesNoAuditEvent() throws Exception {
    // The organization never comes from the request body: it is the whole boundary of this run.
    UUID deletedLibrary = UUID.randomUUID();
    when(cleanupService.reportOrphanedLibraries(actingAdminOrganizationId, 180))
        .thenReturn(
            new OrphanedLibraryReport(
                List.of(
                    new OrphanedLibrary(
                        deletedLibrary,
                        List.of(
                            new OrphanedOriginal(
                                "s3://bucket/uploads/org/lib/x.pdf",
                                Instant.parse("2026-09-01T10:00:00Z"),
                                4711L)),
                        1,
                        2,
                        1,
                        4711L)),
                1,
                5,
                4,
                180));

    mockMvc
        .perform(
            post("/api/v1/admin/upload-store/orphan-libraries/report")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"minimumAgeMinutes\":180}")
                .with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.libraries[0].libraryId").value(deletedLibrary.toString()))
        .andExpect(
            jsonPath("$.libraries[0].orphans[0].locator")
                .value("s3://bucket/uploads/org/lib/x.pdf"))
        .andExpect(jsonPath("$.libraries[0].orphanCount").value(1))
        .andExpect(jsonPath("$.libraries[0].scannedCount").value(2))
        .andExpect(jsonPath("$.libraries[0].withinGracePeriodCount").value(1))
        .andExpect(jsonPath("$.libraries[0].totalSize").value(4711))
        .andExpect(jsonPath("$.libraryCount").value(1))
        .andExpect(jsonPath("$.scannedLibraryCount").value(5))
        .andExpect(jsonPath("$.knownLibraryCount").value(4))
        .andExpect(jsonPath("$.minimumAgeMinutes").value(180))
        .andExpect(jsonPath("$.truncated").value(false));

    verify(cleanupService).reportOrphanedLibraries(actingAdminOrganizationId, 180);
    verifyNoInteractions(auditEventRecorder);
  }

  @Test
  void deletingInAnOrphanedLibraryRecordsOneAuditEventUnderTheSameType() throws Exception {
    when(cleanupService.deleteInOrphanedLibrary(
            actingAdminOrganizationId, libraryId, List.of("locator-1")))
        .thenReturn(new OrphanedOriginalDeletion(List.of("locator-1"), List.of()));

    mockMvc
        .perform(
            post("/api/v1/admin/upload-store/orphan-libraries/delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"libraryId\":\"" + libraryId + "\",\"locators\":[\"locator-1\"]}")
                .with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deleted[0]").value("locator-1"));

    ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventRecorder).recordUserAction(auditCaptor.capture());
    AuditEvent event = auditCaptor.getValue();
    assertThat(event.eventType()).isEqualTo(AuditEventType.UPLOAD_ORPHAN_ORIGINALS_DELETED);
    assertThat(event.objectType()).isEqualTo(AuditObjectType.KNOWLEDGE_LIBRARY);
    assertThat(event.objectId()).isEqualTo(libraryId);
    assertThat(event.organizationId()).isEqualTo(actingAdminOrganizationId);
    assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
    assertThat(event.after())
        .containsEntry("requestedCount", 1)
        .containsEntry("deleted", List.of("locator-1"));
  }

  @Test
  void aStillExistingLibraryIsRejectedAndTheRejectionIsRecorded() throws Exception {
    when(cleanupService.deleteInOrphanedLibrary(any(), any(), any()))
        .thenThrow(new IllegalArgumentException("Die Bibliothek " + libraryId + " existiert"));

    mockMvc
        .perform(
            post("/api/v1/admin/upload-store/orphan-libraries/delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"libraryId\":\"" + libraryId + "\",\"locators\":[\"locator-1\"]}")
                .with(asAdmin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Die Bibliothek " + libraryId + " existiert"));

    ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventRecorder).recordUserAction(auditCaptor.capture());
    assertThat(auditCaptor.getValue().outcome()).isEqualTo(AuditOutcome.FAILURE);
  }

  @Test
  void anEmptyLocatorListReachesTheServiceAndIsRecordedAsARejectedCall() throws Exception {
    // The cap and the emptiness rule live in the service, so the rejected call is an
    // administrative decision that leaves its own FAILURE event - not a malformed request.
    when(cleanupService.delete(any(), any(), any()))
        .thenThrow(new IllegalArgumentException("locators darf nicht leer sein"));

    mockMvc
        .perform(
            post("/api/v1/admin/upload-store/orphan-originals/delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"libraryId\":\"" + libraryId + "\",\"locators\":[]}")
                .with(asAdmin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("locators darf nicht leer sein"));

    verify(auditEventRecorder).recordUserAction(any(AuditEvent.class));
  }
}
