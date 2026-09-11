package io.opaa.auth.local;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.User;
import io.opaa.observability.AuthMetrics;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * The lockout after failed sign-ins (ADR-0033, Entscheidungen 9 and 13): once the atomically kept
 * counter reaches {@code opaa.auth.local.lockout.max-attempts}, the account is locked for the fixed
 * duration ({@code locked_reason = FAILED_LOGINS}, ending by itself), audited once as {@code
 * LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS} under the {@code local-auth} system actor and counted
 * as a metric. The single failed attempt reaches the application log only, with the account id and
 * never the address. The lock resets the counter, so an ended lockout leaves the full budget again;
 * an account that is already locked - a lockout still running, or an administrator's lock - is
 * neither re-locked nor extended ({@code LocalLoginService} counts nothing for it, this is the
 * second line of defence); a concurrent request that locked first is no error. No mail and no
 * session revocation: the validator refuses the tokens of a locked account by its state.
 */
@Component
public class LocalAccountLockoutListener implements LocalLoginAttemptListener {

  private static final Logger log = LoggerFactory.getLogger(LocalAccountLockoutListener.class);

  private final LocalCredentialsRepository credentials;
  private final LocalLockoutProperties properties;
  private final AuditEventRecorder audit;
  private final AuthMetrics metrics;

  public LocalAccountLockoutListener(
      LocalCredentialsRepository credentials,
      LocalLockoutProperties properties,
      AuditEventRecorder audit,
      AuthMetrics metrics) {
    this.credentials = credentials;
    this.properties = properties;
    this.audit = audit;
    this.metrics = metrics;
  }

  @Override
  public void onLoginSucceeded(User user, LocalCredentials row, Instant now) {
    // the counter was reset by the service; nothing to do here
  }

  @Override
  public void onPasswordRejected(User user, LocalCredentials row, Instant now) {
    log.info("Local sign-in of account {} refused: wrong password", user.getId());
    if (row.getFailedLoginAttempts() < properties.maxAttempts()
        || row.state(now) == LocalAccountState.LOCKED) {
      return;
    }
    Instant lockoutUntil = now.plus(properties.duration());
    row.recordLockoutUntil(lockoutUntil, now);
    try {
      credentials.save(row);
    } catch (OptimisticLockingFailureException lockedByAnotherRequest) {
      log.debug("Local account {} was locked by a concurrent request", user.getId());
      return;
    }
    metrics.recordAccountLockedAfterFailedLogins();
    log.warn(
        "Local account {} locked until {} after too many failed sign-ins ({})",
        user.getId(),
        lockoutUntil,
        properties.duration());
    UUID pseudonym = audit.pseudonymFor(user.getId(), user.getOrganizationId());
    audit.recordSystemProcessAction(
        AuditEvent.builder()
            .organizationId(user.getOrganizationId())
            .actorRef(LocalRefreshTokenService.SYSTEM_ACTOR)
            .type(AuditEventType.LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS)
            .object(AuditObjectType.USER_ACCOUNT, pseudonym, null)
            .subject(AuditSubjectKind.USER, user.getId())
            .after(
                Map.of(
                    "lockedReason",
                    LockReason.FAILED_LOGINS.name(),
                    "lockoutUntil",
                    lockoutUntil.toString()))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }
}
