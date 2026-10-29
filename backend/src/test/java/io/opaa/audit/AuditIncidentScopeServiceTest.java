package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditIncidentScopePurpose;
import io.opaa.api.types.AuditObjectType;
import io.opaa.common.ValidationException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The bounds of the anlassbezogene Klärung and its two audit events: the time range lies in the
 * past and spans at most the configured days, and request and approval are each one entry of the
 * audit log that names the grant, never the person.
 */
class AuditIncidentScopeServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");
  private static final String REASON = "Verdacht auf unbefugten Zugriff auf Personalvorgaenge";

  private final AuditIncidentScopeGrantRepository repository =
      mock(AuditIncidentScopeGrantRepository.class);
  private final AuditPersonDirectory people = mock(AuditPersonDirectory.class);
  private final AuditEventRecorder recorder = mock(AuditEventRecorder.class);
  private final UUID organizationId = UUID.randomUUID();
  private final UUID requester = UUID.randomUUID();
  private final UUID approver = UUID.randomUUID();
  private final UUID subject = UUID.randomUUID();

  private AuditIncidentScopeService service;

  @BeforeEach
  void setUp() {
    service =
        new AuditIncidentScopeService(
            repository,
            people,
            recorder,
            new AuditIncidentScopeProperties(30),
            Clock.fixed(NOW, ZoneOffset.UTC));
    when(people.belongsTo(organizationId, subject)).thenReturn(true);
    when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
  }

  /** Regression guard for #3352: a Klärung that ends in the future would watch a person live. */
  @Test
  void aTimeRangeEndingInTheFutureIsRejected() {
    assertThatThrownBy(() -> request(NOW.minus(Duration.ofDays(5)), NOW.plusSeconds(1)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Zukunft");
    verify(repository, never()).save(any());
    verify(recorder, never()).recordUserAction(any());
  }

  /** Regression guard for #3352: the time range of one Klärung is bounded. */
  @Test
  void aTimeRangeLongerThanTheConfiguredDaysIsRejected() {
    assertThatThrownBy(() -> request(NOW.minus(Duration.ofDays(31)), NOW))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("30 Tage");
    verify(repository, never()).save(any());
  }

  @Test
  void aTimeRangeOfExactlyTheConfiguredDaysEndingNowIsAccepted() {
    assertThat(request(NOW.minus(Duration.ofDays(30)), NOW)).isNotNull();
  }

  @Test
  void withoutAConfiguredValueNinetyTwoDaysApply() {
    assertThat(new AuditIncidentScopeProperties(null).maxDays()).isEqualTo(92);
    assertThatThrownBy(() -> new AuditIncidentScopeProperties(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * Regression guard for #3352: the request is an entry of the audit log, so the checksum chain
   * secures who asked; it names the grant and its range, not the person.
   */
  @Test
  void theRequestIsRecordedAgainstTheGrantWithoutThePerson() {
    Instant start = NOW.minus(Duration.ofDays(10));
    AuditIncidentScopeGrant grant = request(start, NOW.minus(Duration.ofDays(1)));

    AuditEvent event = recorded();
    assertThat(event.eventType()).isEqualTo(AuditEventType.INCIDENT_SCOPE_REQUESTED);
    assertThat(event.actorUserId()).isEqualTo(requester);
    assertThat(event.objectType()).isEqualTo(AuditObjectType.AUDIT_LOG);
    assertThat(event.objectId()).isEqualTo(grant.getId());
    assertThat(event.subjectId()).isNull();
    assertThat(event.after())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "purpose", "SECURITY_INCIDENT",
                "scopeStart", start.toString(),
                "scopeEnd", NOW.minus(Duration.ofDays(1)).toString()));
    assertThat(event.after().values()).doesNotContain(subject.toString(), REASON);
  }

  /** Regression guard for #3352: the approval, too, is an entry of the audit log. */
  @Test
  void theApprovalIsRecordedAgainstTheGrantByTheApprover() {
    AuditIncidentScopeGrant grant =
        new AuditIncidentScopeGrant(
            organizationId,
            subject,
            NOW.minus(Duration.ofDays(10)),
            NOW.minus(Duration.ofDays(1)),
            AuditIncidentScopePurpose.DATA_BREACH_INVESTIGATION,
            REASON,
            requester);
    when(repository.findForApproval(grant.getId(), organizationId)).thenReturn(Optional.of(grant));

    service.approve(organizationId, grant.getId(), approver);

    AuditEvent event = recorded();
    assertThat(event.eventType()).isEqualTo(AuditEventType.INCIDENT_SCOPE_APPROVED);
    assertThat(event.actorUserId()).isEqualTo(approver);
    assertThat(event.objectId()).isEqualTo(grant.getId());
    assertThat(event.subjectId()).isNull();
    assertThat(event.after())
        .containsEntry("purpose", "DATA_BREACH_INVESTIGATION")
        .containsEntry("usableUntil", NOW.plus(AuditIncidentScopeGrant.USABLE_WINDOW).toString());
  }

  @Test
  void aRejectedSelfApprovalRecordsNothing() {
    AuditIncidentScopeGrant grant =
        new AuditIncidentScopeGrant(
            organizationId,
            subject,
            NOW.minus(Duration.ofDays(10)),
            NOW.minus(Duration.ofDays(1)),
            AuditIncidentScopePurpose.SECURITY_INCIDENT,
            REASON,
            requester);
    when(repository.findForApproval(grant.getId(), organizationId)).thenReturn(Optional.of(grant));

    assertThatThrownBy(() -> service.approve(organizationId, grant.getId(), requester))
        .isInstanceOf(ValidationException.class);
    verify(recorder, never()).recordUserAction(any());
    verify(repository, never()).countTransition(any(), any(), anyString(), anyString());
  }

  private AuditIncidentScopeGrant request(Instant start, Instant end) {
    return service.request(
        organizationId,
        requester,
        subject,
        start,
        end,
        AuditIncidentScopePurpose.SECURITY_INCIDENT,
        REASON);
  }

  private AuditEvent recorded() {
    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
    verify(recorder).recordUserAction(event.capture());
    return event.getValue();
  }
}
