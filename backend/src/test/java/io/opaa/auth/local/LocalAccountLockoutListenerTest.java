package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.LocalAccountState;
import io.opaa.api.types.LockReason;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.User;
import io.opaa.observability.AuthMetrics;
import io.opaa.organization.Organization;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link LocalAccountLockoutListener} (ADR-0033, Entscheidungen 9 and 13): the fifth failed sign-in
 * locks the account for the fixed lockout duration ({@code locked_reason = FAILED_LOGINS}) and is
 * the one audited event of the whole affair - the single failed attempt only reaches the
 * application log, with the account id and never the address. An account that is already locked,
 * for whatever reason, is neither re-locked nor extended; a concurrent lock by another request is
 * not an error.
 */
class LocalAccountLockoutListenerTest {

  private static final Instant NOW = Instant.parse("2026-09-11T10:00:00Z");
  private static final String EMAIL = "erika.muster@stadt.example";
  private static final UUID PSEUDONYM = UUID.randomUUID();

  private final LocalCredentialsRepository repository = mock(LocalCredentialsRepository.class);
  private final AuditEventRecorder audit = mock(AuditEventRecorder.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final AuthMetrics metrics = new AuthMetrics(meterRegistry);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private Logger logger;
  private LocalAccountLockoutListener listener;
  private User user;

  @BeforeEach
  void setUp() {
    listener =
        new LocalAccountLockoutListener(
            repository, new LocalLockoutProperties(5, Duration.ofMinutes(15)), audit, metrics);
    user = User.localAccount(EMAIL, "Erika Muster");
    user.setOrganizationId(Organization.DEFAULT_ID);
    ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
    when(audit.pseudonymFor(user.getId(), Organization.DEFAULT_ID)).thenReturn(PSEUDONYM);
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    logger = (Logger) LoggerFactory.getLogger(LocalAccountLockoutListener.class);
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(logs);
  }

  private LocalCredentials activeRowWithFailedAttempts(int attempts) {
    LocalCredentials row = new LocalCredentials(user.getId(), "Testkonto", NOW.minusSeconds(3600));
    row.setPasswordHash("{bcrypt}$2a$12$stored", NOW.minusSeconds(3600));
    row.markEmailVerified(NOW.minusSeconds(3600));
    ReflectionTestUtils.setField(row, "failedLoginAttempts", attempts);
    return row;
  }

  @Test
  void belowTheThresholdNothingIsLockedAndNothingIsLogged() {
    LocalCredentials row = activeRowWithFailedAttempts(4);

    listener.onPasswordRejected(user, row, NOW);

    assertThat(row.state(NOW)).isEqualTo(LocalAccountState.ACTIVE);
    verify(repository, never()).save(any());
    verify(audit, never()).recordSystemProcessAction(any());
    assertThat(logs.list).as("the attempt's log line belongs to LocalLoginService").isEmpty();
    assertThat(lockouts()).isZero();
  }

  @Test
  void theFifthFailedAttemptLocksForTheFixedDurationAndAuditsOnce() {
    LocalCredentials row = activeRowWithFailedAttempts(5);

    listener.onPasswordRejected(user, row, NOW);

    assertThat(row.state(NOW)).isEqualTo(LocalAccountState.LOCKED);
    assertThat(row.getLockedReason()).isEqualTo(LockReason.FAILED_LOGINS);
    assertThat(row.getLockedAt()).isEqualTo(NOW);
    assertThat(row.getLockoutUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    assertThat(row.state(NOW.plus(Duration.ofMinutes(15)))).isEqualTo(LocalAccountState.ACTIVE);
    assertThat(row.getFailedLoginAttempts()).as("the lock resets the counter").isZero();
    verify(repository).save(row);

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).recordSystemProcessAction(captor.capture());
    AuditEvent event = captor.getValue();
    assertThat(event.eventType())
        .isEqualTo(AuditEventType.LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS);
    assertThat(event.actorRef()).isEqualTo(LocalRefreshTokenService.SYSTEM_ACTOR);
    assertThat(event.objectType()).isEqualTo(AuditObjectType.USER_ACCOUNT);
    assertThat(event.objectId()).isEqualTo(PSEUDONYM);
    assertThat(event.subjectKind()).isEqualTo(AuditSubjectKind.USER);
    assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
    assertThat(event.after())
        .containsEntry("lockedReason", LockReason.FAILED_LOGINS.name())
        .containsEntry("lockoutUntil", NOW.plus(Duration.ofMinutes(15)).toString())
        .doesNotContainKey("failedLoginAttempts");
    assertThat(String.valueOf(event.after())).doesNotContain(EMAIL);
    assertThat(lockouts()).isEqualTo(1.0);
    assertThat(logs.list)
        .filteredOn(event2 -> event2.getLevel() == Level.WARN)
        .extracting(ILoggingEvent::getFormattedMessage)
        .anySatisfy(
            message -> assertThat(message).contains(user.getId().toString()).doesNotContain(EMAIL));
  }

  @Test
  void anAttemptDuringTheLockoutNeitherExtendsItNorAuditsAgain() {
    LocalCredentials row = activeRowWithFailedAttempts(7);
    Instant lockedAt = NOW.minus(Duration.ofMinutes(5));
    row.recordLockoutUntil(lockedAt.plus(Duration.ofMinutes(15)), lockedAt);

    listener.onPasswordRejected(user, row, NOW);

    assertThat(row.getLockoutUntil()).isEqualTo(lockedAt.plus(Duration.ofMinutes(15)));
    assertThat(row.getLockedAt()).isEqualTo(lockedAt);
    verify(repository, never()).save(any());
    verify(audit, never()).recordSystemProcessAction(any());
    assertThat(lockouts()).isZero();
  }

  @Test
  void anAdministratorsLockIsNeverOverwrittenByAFailedLoginLock() {
    LocalCredentials row = activeRowWithFailedAttempts(9);
    row.lock(LockReason.ADMIN, NOW.minus(Duration.ofDays(1)), null);

    listener.onPasswordRejected(user, row, NOW);

    assertThat(row.getLockedReason()).isEqualTo(LockReason.ADMIN);
    assertThat(row.getLockoutUntil()).isNull();
    verify(repository, never()).save(any());
    verify(audit, never()).recordSystemProcessAction(any());
  }

  @Test
  void afterAnExpiredLockoutTheAccountHasItsFullBudgetAgain() {
    // ADR-0033, Entscheidung 9: five attempts, then a fixed lockout - not one attempt per lockout
    LocalCredentials row = activeRowWithFailedAttempts(0);
    Instant lockedAt = NOW.minus(Duration.ofMinutes(30));
    row.recordLockoutUntil(lockedAt.plus(Duration.ofMinutes(15)), lockedAt);
    assertThat(row.state(NOW)).isEqualTo(LocalAccountState.ACTIVE);
    ReflectionTestUtils.setField(row, "failedLoginAttempts", 4);

    listener.onPasswordRejected(user, row, NOW);

    assertThat(row.state(NOW)).isEqualTo(LocalAccountState.ACTIVE);
    verify(repository, never()).save(any());
    verify(audit, never()).recordSystemProcessAction(any());

    ReflectionTestUtils.setField(row, "failedLoginAttempts", 5);
    listener.onPasswordRejected(user, row, NOW);

    assertThat(row.state(NOW)).isEqualTo(LocalAccountState.LOCKED);
    assertThat(row.getLockoutUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    verify(repository).save(row);
    verify(audit).recordSystemProcessAction(any());
  }

  @Test
  void aConcurrentLockByAnotherRequestIsNoErrorAndNotAuditedTwice() {
    LocalCredentials row = activeRowWithFailedAttempts(5);
    when(repository.save(any()))
        .thenThrow(new ObjectOptimisticLockingFailureException(LocalCredentials.class, row));

    listener.onPasswordRejected(user, row, NOW);

    verify(audit, never()).recordSystemProcessAction(any());
    assertThat(lockouts()).isZero();
  }

  @Test
  void aSuccessfulSignInIsNoneOfItsBusiness() {
    LocalCredentials row = activeRowWithFailedAttempts(0);

    listener.onLoginSucceeded(user, row, NOW);

    verify(repository, never()).save(any());
    verify(audit, never()).recordSystemProcessAction(any());
  }

  private double lockouts() {
    return meterRegistry.counter(AuthMetrics.ACCOUNT_LOCKOUT_METRIC).count();
  }
}
