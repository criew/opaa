package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ActorKind;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pins the field mapping {@link AuditEventRecorder}'s three {@code recordXxx} methods perform from
 * an {@link AuditEvent} onto {@link AuditLogEntry} (#892) - the same fields the pre-builder,
 * 10-13-positional-parameter signatures wrote, now assembled from named builder calls instead of
 * argument position. End-to-end field pinning against a real, versioned schema per calling service
 * lives in {@code AuditEventRecordingIntegrationTest}; this class instead pins {@link
 * AuditEventRecorder}'s own translation and the builder's required-field validation in isolation.
 */
class AuditEventRecorderTest {

  private AuditLogService auditLogService;
  private AuditActorPseudonymService pseudonymService;
  private AuditEventRecorder recorder;

  @BeforeEach
  void setUp() {
    auditLogService = mock(AuditLogService.class);
    pseudonymService = mock(AuditActorPseudonymService.class);
    recorder = new AuditEventRecorder(auditLogService, pseudonymService);
  }

  @Test
  void recordUserActionMapsEveryFieldOntoAWithoutSubjectEntry() {
    UUID organizationId = UUID.randomUUID();
    UUID actorUserId = UUID.randomUUID();
    UUID actorPseudonym = UUID.randomUUID();
    UUID objectId = UUID.randomUUID();
    when(pseudonymService.pseudonymFor(actorUserId, organizationId)).thenReturn(actorPseudonym);

    recorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(actorUserId)
            .type(AuditEventType.SPACE_CREATED)
            .object(AuditObjectType.SPACE, objectId, "Team Alpha")
            .before(Map.of("k", "before"))
            .after(Map.of("k", "after"))
            .outcome(AuditOutcome.SUCCESS)
            .reason("weil")
            .build());

    ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
    verify(auditLogService).record(captor.capture());
    AuditLogEntry entry = captor.getValue();
    assertThat(entry.getOrganizationId()).isEqualTo(organizationId);
    assertThat(entry.getActorKind()).isEqualTo(ActorKind.USER);
    assertThat(entry.getActorRef()).isEqualTo(actorPseudonym.toString());
    assertThat(entry.getEventType()).isEqualTo(AuditEventType.SPACE_CREATED);
    assertThat(entry.getObjectType()).isEqualTo(AuditObjectType.SPACE);
    assertThat(entry.getObjectId()).isEqualTo(objectId.toString());
    assertThat(entry.getObjectLabel()).isEqualTo("Team Alpha");
    assertThat(entry.getSubjectKind()).isNull();
    assertThat(entry.getBefore()).contains("before");
    assertThat(entry.getAfter()).contains("after");
    assertThat(entry.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
    assertThat(entry.getReason()).isEqualTo("weil");
    assertThat(entry.getCorrelationRef()).isNull();
  }

  @Test
  void recordUserActionOnSubjectMapsTheSubjectAndPseudonymisesAUserSubject() {
    UUID organizationId = UUID.randomUUID();
    UUID actorUserId = UUID.randomUUID();
    UUID subjectUserId = UUID.randomUUID();
    UUID subjectPseudonym = UUID.randomUUID();
    UUID objectId = UUID.randomUUID();
    when(pseudonymService.pseudonymFor(actorUserId, organizationId)).thenReturn(UUID.randomUUID());
    when(pseudonymService.pseudonymFor(subjectUserId, organizationId)).thenReturn(subjectPseudonym);

    recorder.recordUserActionOnSubject(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(actorUserId)
            .type(AuditEventType.SPACE_MEMBER_ADDED)
            .object(AuditObjectType.SPACE, objectId, "Team Alpha")
            .subject(AuditSubjectKind.USER, subjectUserId)
            .after(Map.of("role", "MEMBER"))
            .outcome(AuditOutcome.SUCCESS)
            .build());

    ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
    verify(auditLogService).record(captor.capture());
    AuditLogEntry entry = captor.getValue();
    assertThat(entry.getSubjectKind()).isEqualTo(AuditSubjectKind.USER);
    assertThat(entry.getSubjectRef()).isEqualTo(subjectPseudonym.toString());
  }

  @Test
  void recordUserActionOnSubjectReferencesAGroupSubjectByItsPlainId() {
    UUID organizationId = UUID.randomUUID();
    UUID actorUserId = UUID.randomUUID();
    UUID subjectGroupId = UUID.randomUUID();
    when(pseudonymService.pseudonymFor(actorUserId, organizationId)).thenReturn(UUID.randomUUID());

    recorder.recordUserActionOnSubject(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(actorUserId)
            .type(AuditEventType.ASSET_GRANT_GRANTED)
            .object(AuditObjectType.KNOWLEDGE_LIBRARY, UUID.randomUUID(), "Bibliothek")
            .subject(AuditSubjectKind.GROUP, subjectGroupId)
            .after(Map.of("role", "MANAGER"))
            .outcome(AuditOutcome.SUCCESS)
            .build());

    ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
    verify(auditLogService).record(captor.capture());
    // A group is not a person - referenced by its plain id, never pseudonymised.
    assertThat(captor.getValue().getSubjectRef()).isEqualTo(subjectGroupId.toString());
    verify(pseudonymService, never()).pseudonymFor(subjectGroupId, organizationId);
  }

  @Test
  void recordSystemProcessActionWithAUserSubjectPseudonymisesIt() {
    UUID organizationId = UUID.randomUUID();
    UUID subjectUserId = UUID.randomUUID();
    UUID subjectPseudonym = UUID.randomUUID();
    when(pseudonymService.pseudonymFor(subjectUserId, organizationId)).thenReturn(subjectPseudonym);

    recorder.recordSystemProcessAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actorRef("directory-sync")
            .type(AuditEventType.DIRECTORY_SYNC_CHANGE_APPLIED)
            .object(AuditObjectType.GROUP, UUID.randomUUID(), "Referat 5")
            .subject(AuditSubjectKind.USER, subjectUserId)
            .outcome(AuditOutcome.SUCCESS)
            .correlationRef("run-1")
            .build());

    ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
    verify(auditLogService).record(captor.capture());
    AuditLogEntry entry = captor.getValue();
    assertThat(entry.getSubjectKind()).isEqualTo(AuditSubjectKind.USER);
    assertThat(entry.getSubjectRef()).isEqualTo(subjectPseudonym.toString());
    assertThat(entry.getActorKind()).isEqualTo(ActorKind.SYSTEM_PROCESS);
  }

  @Test
  void recordSystemProcessActionWithAGroupSubjectReferencesItByItsPlainId() {
    UUID organizationId = UUID.randomUUID();
    UUID subjectGroupId = UUID.randomUUID();

    recorder.recordSystemProcessAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actorRef("directory-sync")
            .type(AuditEventType.DIRECTORY_SYNC_CHANGE_APPLIED)
            .object(AuditObjectType.GROUP, UUID.randomUUID(), "Referat 5")
            .subject(AuditSubjectKind.GROUP, subjectGroupId)
            .outcome(AuditOutcome.SUCCESS)
            .correlationRef("run-1")
            .build());

    ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
    verify(auditLogService).record(captor.capture());
    assertThat(captor.getValue().getSubjectRef()).isEqualTo(subjectGroupId.toString());
    verify(pseudonymService, never()).pseudonymFor(subjectGroupId, organizationId);
  }

  @Test
  void recordUserActionRejectsAnEventBuiltWithASubject() {
    UUID organizationId = UUID.randomUUID();
    UUID actorUserId = UUID.randomUUID();
    when(pseudonymService.pseudonymFor(any(), any())).thenReturn(UUID.randomUUID());
    AuditEvent event =
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(actorUserId)
            .type(AuditEventType.SPACE_CREATED)
            .object(AuditObjectType.SPACE, UUID.randomUUID(), "Team Alpha")
            .subject(AuditSubjectKind.USER, UUID.randomUUID())
            .outcome(AuditOutcome.SUCCESS)
            .build();

    // A subject built into the event but silently dropped would be the compliance-log equivalent
    // of a swallowed field - recordUserAction must fail loudly instead of writing a
    // without-subject entry for a caller that plainly meant recordUserActionOnSubject.
    assertThatThrownBy(() -> recorder.recordUserAction(event))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("recordUserActionOnSubject");
  }

  @Test
  void recordUserActionRejectsAnEventBuiltWithACorrelationRef() {
    UUID organizationId = UUID.randomUUID();
    UUID actorUserId = UUID.randomUUID();
    when(pseudonymService.pseudonymFor(any(), any())).thenReturn(UUID.randomUUID());
    AuditEvent event =
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(actorUserId)
            .type(AuditEventType.SPACE_CREATED)
            .object(AuditObjectType.SPACE, UUID.randomUUID(), "Team Alpha")
            .outcome(AuditOutcome.SUCCESS)
            .correlationRef("run-1")
            .build();

    assertThatThrownBy(() -> recorder.recordUserAction(event))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("correlationRef");
  }

  @Test
  void recordUserActionOnSubjectRejectsAnEventBuiltWithACorrelationRef() {
    UUID organizationId = UUID.randomUUID();
    UUID actorUserId = UUID.randomUUID();
    when(pseudonymService.pseudonymFor(any(), any())).thenReturn(UUID.randomUUID());
    AuditEvent event =
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(actorUserId)
            .type(AuditEventType.SPACE_MEMBER_ADDED)
            .object(AuditObjectType.SPACE, UUID.randomUUID(), "Team Alpha")
            .subject(AuditSubjectKind.USER, UUID.randomUUID())
            .outcome(AuditOutcome.SUCCESS)
            .correlationRef("run-1")
            .build();

    assertThatThrownBy(() -> recorder.recordUserActionOnSubject(event))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("correlationRef");
  }

  @Test
  void recordSystemProcessActionUsesTheActorRefVerbatimAndCarriesTheCorrelationRef() {
    UUID organizationId = UUID.randomUUID();
    UUID objectId = UUID.randomUUID();

    recorder.recordSystemProcessAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actorRef("directory-sync")
            .type(AuditEventType.DIRECTORY_SYNC_RUN_COMPLETED)
            .object(AuditObjectType.DIRECTORY_SYNC_RUN, objectId, "Verzeichnisabgleich")
            .outcome(AuditOutcome.SUCCESS)
            .correlationRef("run-1")
            .build());

    ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
    verify(auditLogService).record(captor.capture());
    AuditLogEntry entry = captor.getValue();
    assertThat(entry.getActorKind()).isEqualTo(ActorKind.SYSTEM_PROCESS);
    assertThat(entry.getActorRef()).isEqualTo("directory-sync");
    assertThat(entry.getCorrelationRef()).isEqualTo("run-1");
    assertThat(entry.getSubjectKind()).isNull();
  }

  @Test
  void buildingWithoutARequiredFieldFailsFast() {
    assertThatThrownBy(
            () ->
                AuditEvent.builder()
                    .organizationId(UUID.randomUUID())
                    .actor(UUID.randomUUID())
                    .type(AuditEventType.SPACE_CREATED)
                    // no .object(...) call - objectType/objectId stay null.
                    .outcome(AuditOutcome.SUCCESS)
                    .build())
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void recordUserActionRejectsAnEventBuiltWithoutAnActor() {
    AuditEvent event =
        AuditEvent.builder()
            .organizationId(UUID.randomUUID())
            .type(AuditEventType.SPACE_CREATED)
            .object(AuditObjectType.SPACE, UUID.randomUUID(), "Team Alpha")
            .outcome(AuditOutcome.SUCCESS)
            .build();

    assertThatThrownBy(() -> recorder.recordUserAction(event))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void recordUserActionOnSubjectRejectsAnEventBuiltWithoutASubject() {
    UUID organizationId = UUID.randomUUID();
    UUID actorUserId = UUID.randomUUID();
    when(pseudonymService.pseudonymFor(any(), any())).thenReturn(UUID.randomUUID());
    AuditEvent event =
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(actorUserId)
            .type(AuditEventType.SPACE_MEMBER_ADDED)
            .object(AuditObjectType.SPACE, UUID.randomUUID(), "Team Alpha")
            .outcome(AuditOutcome.SUCCESS)
            .build();

    assertThatThrownBy(() -> recorder.recordUserActionOnSubject(event))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void recordSystemProcessActionRejectsAnEventBuiltWithoutAnActorRef() {
    AuditEvent event =
        AuditEvent.builder()
            .organizationId(UUID.randomUUID())
            .type(AuditEventType.DIRECTORY_SYNC_RUN_COMPLETED)
            .object(AuditObjectType.DIRECTORY_SYNC_RUN, UUID.randomUUID(), "Verzeichnisabgleich")
            .outcome(AuditOutcome.SUCCESS)
            .build();

    assertThatThrownBy(() -> recorder.recordSystemProcessAction(event))
        .isInstanceOf(NullPointerException.class);
  }
}
