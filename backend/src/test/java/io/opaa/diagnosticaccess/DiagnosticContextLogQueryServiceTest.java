package io.opaa.diagnosticaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.DiagnosticTargetKind;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditActorPseudonymService;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/** Leitplanken (g) and (h): who may read the protocol, and how narrowly. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DiagnosticContextLogQueryServiceTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();
  private static final Instant FROM = Instant.parse("2026-08-01T00:00:00Z");

  @Mock private DiagnosticContextLogRepository logRepository;
  @Mock private AuditActorPseudonymService pseudonymService;
  @Mock private UserRepository userRepository;
  @Mock private AuditEventRecorder auditEventRecorder;

  private DiagnosticContextLogQueryService service;

  private final UUID callerId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service =
        new DiagnosticContextLogQueryService(
            logRepository, pseudonymService, userRepository, auditEventRecorder);
    when(logRepository.findByTimeRange(any(), any(), any(), any()))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));
  }

  @Test
  void aPersonSeesWhenAndByWhomTheirContextWasAssumed() {
    UUID ownPseudonym = UUID.randomUUID();
    UUID actorPseudonym = UUID.randomUUID();
    UUID actorUserId = UUID.randomUUID();
    when(pseudonymService.findExistingPseudonym(callerId)).thenReturn(Optional.of(ownPseudonym));
    when(pseudonymService.findUserByPseudonym(actorPseudonym)).thenReturn(Optional.of(actorUserId));
    when(userRepository.findById(actorUserId))
        .thenReturn(Optional.of(new User("s", "i", null, "Frau Beispiel")));
    when(logRepository.findOwnEntries(
            eq(ORGANIZATION_ID), eq(DiagnosticTargetKind.USER), eq(ownPseudonym.toString()), any()))
        .thenReturn(
            new PageImpl<>(
                List.of(entry(actorPseudonym, ownPseudonym.toString())), PageRequest.of(0, 50), 1));

    Page<OwnDiagnosticContextEvent> events = service.findOwnEvents(caller(SystemRole.USER), 0, 50);

    assertThat(events.getContent()).hasSize(1);
    assertThat(events.getContent().getFirst().actorDisplayName()).isEqualTo("Frau Beispiel");
    assertThat(events.getContent().getFirst().justification()).isEqualTo("Beschwerde 4711");
  }

  @Test
  void aPersonWithoutAnyEntriesSeesAnEmptyPageRatherThanAnError() {
    when(pseudonymService.findExistingPseudonym(callerId)).thenReturn(Optional.empty());

    assertThat(service.findOwnEvents(caller(SystemRole.USER), 0, 50)).isEmpty();
    verify(logRepository, never()).findOwnEntries(any(), any(), any(), any());
  }

  @Test
  void aFachvorgesetzterReachesNothingInTheGesamtprotokoll() {
    assertThatThrownBy(
            () ->
                service.findByTimeRange(
                    caller(SystemRole.SYSTEM_ADMIN),
                    FROM,
                    FROM.plus(1, ChronoUnit.DAYS),
                    "Beschwerde",
                    0,
                    50))
        .isInstanceOf(AccessDeniedException.class);

    verify(auditEventRecorder)
        .recordAuditLogAccess(
            eq(ORGANIZATION_ID), eq(callerId), any(), eq(AuditOutcome.DENIED), eq("Beschwerde"));
  }

  @Test
  void anAuditorNeedsAReason() {
    assertThatThrownBy(
            () ->
                service.findByTimeRange(
                    caller(SystemRole.AUDITOR), FROM, FROM.plus(1, ChronoUnit.DAYS), "  ", 0, 50))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void anAuditorCannotRequestAnUnboundedWindow() {
    assertThatThrownBy(
            () ->
                service.findByTimeRange(
                    caller(SystemRole.AUDITOR),
                    FROM,
                    FROM.plus(365, ChronoUnit.DAYS),
                    "Beschwerde",
                    0,
                    50))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void anAuditorWithReasonAndBoundedWindowReadsTheProtocolAndTheReadIsRecorded() {
    service.findByTimeRange(
        caller(SystemRole.AUDITOR), FROM, FROM.plus(7, ChronoUnit.DAYS), "Beschwerde 4711", 0, 50);

    ArgumentCaptor<Map<String, Object>> scope = ArgumentCaptor.captor();
    verify(auditEventRecorder)
        .recordAuditLogAccess(
            eq(ORGANIZATION_ID),
            eq(callerId),
            scope.capture(),
            eq(AuditOutcome.SUCCESS),
            eq("Beschwerde 4711"));
    assertThat(scope.getValue())
        .containsEntry("accessPath", "diagnostic-context-events")
        .containsKeys("from", "to");
  }

  /**
   * Regression guard for the review of #1121: the DENIED entry must be written through {@link
   * AuditEventRecorder#recordAuditLogAccess}, whose suspended transaction survives the rejection -
   * {@code recordUserAction} joins the caller's transaction, which the thrown exception rolls back.
   * {@code DiagnosticAccessIntegrationTest} proves the surviving row against a real transaction;
   * this assertion keeps a future edit from silently swapping the method back.
   */
  @Test
  void aRejectedAccessIsNeverRecordedThroughATransactionJoiningPath() {
    assertThatThrownBy(
            () ->
                service.findByTimeRange(
                    caller(SystemRole.SYSTEM_ADMIN),
                    FROM,
                    FROM.plus(1, ChronoUnit.DAYS),
                    "Beschwerde",
                    0,
                    50))
        .isInstanceOf(AccessDeniedException.class);

    verify(auditEventRecorder, never()).recordUserAction(any());
  }

  @Test
  void aFachvorgesetzterReachesNoSingleEntryEither() {
    assertThatThrownBy(
            () ->
                service.findSingleEvent(
                    caller(SystemRole.SYSTEM_ADMIN), UUID.randomUUID(), "Beschwerde"))
        .isInstanceOf(AccessDeniedException.class);

    verify(auditEventRecorder)
        .recordAuditLogAccess(
            eq(ORGANIZATION_ID), eq(callerId), any(), eq(AuditOutcome.DENIED), eq("Beschwerde"));
    verify(logRepository, never()).findSingleEntry(any(), any());
  }

  @Test
  void aSingleEntryNeedsItsOwnReason() {
    assertThatThrownBy(
            () -> service.findSingleEvent(caller(SystemRole.AUDITOR), UUID.randomUUID(), "  "))
        .isInstanceOf(ValidationException.class);

    verify(logRepository, never()).findSingleEntry(any(), any());
  }

  @Test
  void anUnknownEntryIsNotFoundAndTheAttemptIsStillRecorded() {
    UUID eventId = UUID.randomUUID();
    when(logRepository.findSingleEntry(ORGANIZATION_ID, eventId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service.findSingleEvent(caller(SystemRole.AUDITOR), eventId, "Beschwerde 4711"))
        .isInstanceOf(NotFoundException.class);

    verify(auditEventRecorder)
        .recordAuditLogAccess(
            eq(ORGANIZATION_ID),
            eq(callerId),
            any(),
            eq(AuditOutcome.DENIED),
            eq("Beschwerde 4711"));
  }

  /**
   * The einzelfall- und anlassbezogene Auswertung of Leitplanke (g): one already-known entry, the
   * same AUDITOR bar as the Gesamtprotokoll, and its own audit_log record naming the entry.
   */
  @Test
  void anAuditorReadsOneKnownEntryUnderItsOwnAnlassAndTheReadIsRecorded() {
    UUID ownPseudonym = UUID.randomUUID();
    DiagnosticContextLogEntry stored = entry(UUID.randomUUID(), ownPseudonym.toString());
    when(logRepository.findSingleEntry(ORGANIZATION_ID, stored.getEventId()))
        .thenReturn(Optional.of(stored));

    DiagnosticContextLogEntry found =
        service.findSingleEvent(caller(SystemRole.AUDITOR), stored.getEventId(), "Beschwerde 4711");

    assertThat(found).isSameAs(stored);
    ArgumentCaptor<Map<String, Object>> scope = ArgumentCaptor.captor();
    verify(auditEventRecorder)
        .recordAuditLogAccess(
            eq(ORGANIZATION_ID),
            eq(callerId),
            scope.capture(),
            eq(AuditOutcome.SUCCESS),
            eq("Beschwerde 4711"));
    assertThat(scope.getValue())
        .containsEntry("accessPath", "diagnostic-context-events/{eventId}")
        .containsEntry("eventId", stored.getEventId().toString());
  }

  /**
   * A query that breaks after role and reason were accepted is a malfunction, not a turned-away
   * attempt - recording it as DENIED would put it in the trail next to attempted overreach.
   */
  @Test
  void aFailingQueryAfterAPassedCheckIsRecordedAsFailureRatherThanDenied() {
    when(logRepository.findByTimeRange(any(), any(), any(), any()))
        .thenThrow(new QueryTimeoutException("statement timeout"));

    assertThatThrownBy(
            () ->
                service.findByTimeRange(
                    caller(SystemRole.AUDITOR),
                    FROM,
                    FROM.plus(1, ChronoUnit.DAYS),
                    "Beschwerde 4711",
                    0,
                    50))
        .isInstanceOf(QueryTimeoutException.class);

    verify(auditEventRecorder)
        .recordAuditLogAccess(
            eq(ORGANIZATION_ID),
            eq(callerId),
            any(),
            eq(AuditOutcome.FAILURE),
            eq("Beschwerde 4711"));
  }

  private DiagnosticContextLogEntry entry(UUID actorPseudonym, String targetRef) {
    return new DiagnosticContextLogEntry(
        ORGANIZATION_ID,
        actorPseudonym.toString(),
        DiagnosticTargetKind.USER,
        targetRef,
        "Wo steht die Dienstanweisung?",
        1,
        "chunk-1",
        "libraries=[];lockedLibraries=[]",
        "Beschwerde 4711");
  }

  private CurrentUser caller(SystemRole role) {
    return CurrentUser.of(callerId, ORGANIZATION_ID, role, "Aufrufende");
  }
}
