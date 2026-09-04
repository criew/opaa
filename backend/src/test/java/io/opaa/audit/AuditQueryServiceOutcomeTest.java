package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.QueryTimeoutException;

/**
 * Which outcome the self-log records: DENIED means the attempt was turned away, FAILURE means it
 * passed every check and the query itself broke. Conflating the two would make a malfunction
 * indistinguishable from an attempted overreach for whoever reads the trail.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditQueryServiceOutcomeTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();
  private static final Instant TO = Instant.parse("2026-09-01T00:00:00Z");

  @Mock private AuditLogRepository auditLogRepository;
  @Mock private AuditIncidentScopeService incidentScopeService;
  @Mock private AuditActorPseudonymService pseudonymService;
  @Mock private AuditEventRecorder eventRecorder;
  @Mock private UserRepository userRepository;

  private AuditQueryService service;

  private final UUID callerId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service =
        new AuditQueryService(
            auditLogRepository,
            incidentScopeService,
            pseudonymService,
            eventRecorder,
            userRepository);
  }

  @Test
  void aFailingQueryAfterAPassedCheckIsRecordedAsFailureRatherThanDenied() {
    callerIsAn(SystemRole.AUDITOR);
    when(auditLogRepository.findByOrganizationIdAndRecordedAtBetween(any(), any(), any(), any()))
        .thenThrow(new QueryTimeoutException("statement timeout"));

    assertThatThrownBy(
            () ->
                service.byTimeRange(
                    ORGANIZATION_ID,
                    callerId,
                    "Beschwerde 4711",
                    TO.minus(1, ChronoUnit.DAYS),
                    TO,
                    0,
                    50))
        .isInstanceOf(QueryTimeoutException.class);

    verify(eventRecorder)
        .recordAuditLogAccess(
            eq(ORGANIZATION_ID),
            eq(callerId),
            any(),
            eq(AuditOutcome.FAILURE),
            eq("Beschwerde 4711"));
  }

  @Test
  void aRejectedAttemptStaysDenied() {
    callerIsAn(SystemRole.USER);

    assertThatThrownBy(
            () ->
                service.byTimeRange(
                    ORGANIZATION_ID,
                    callerId,
                    "Beschwerde 4711",
                    TO.minus(1, ChronoUnit.DAYS),
                    TO,
                    0,
                    50))
        .isInstanceOf(AccessDeniedException.class);

    verify(eventRecorder)
        .recordAuditLogAccess(
            eq(ORGANIZATION_ID),
            eq(callerId),
            any(),
            eq(AuditOutcome.DENIED),
            eq("Beschwerde 4711"));
  }

  @Test
  void aMalformedRequestStaysDeniedToo() {
    callerIsAn(SystemRole.AUDITOR);

    assertThatThrownBy(
            () ->
                service.byTimeRange(ORGANIZATION_ID, callerId, "Beschwerde 4711", null, TO, 0, 50))
        .isInstanceOf(IllegalArgumentException.class);

    verify(eventRecorder)
        .recordAuditLogAccess(
            eq(ORGANIZATION_ID),
            eq(callerId),
            any(),
            eq(AuditOutcome.DENIED),
            eq("Beschwerde 4711"));
  }

  private void callerIsAn(SystemRole role) {
    User caller = new User("subject", "issuer", null, "Aufrufende");
    caller.setSystemRole(role);
    when(userRepository.findByIdAndOrganizationId(callerId, ORGANIZATION_ID))
        .thenReturn(Optional.of(caller));
  }
}
