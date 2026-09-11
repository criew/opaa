package io.opaa.auth.local;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.User;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one audited sign-in (ADR-0033, Entscheidungen 5 and 13): every successful sign-in with the
 * bootstrap account - identified by {@code is_bootstrap}, never by its address - is {@code
 * LOCAL_BOOTSTRAP_ACCOUNT_LOGIN} under the {@code local-auth} system actor and is handed to the
 * {@link BootstrapLoginNotifier}. A personal account's sign-in is a person's and leaves no event; a
 * rejected password is a security event for the application log (#1535), not for the audit trail.
 */
@Component
public class BootstrapAccountLoginListener implements LocalLoginAttemptListener {

  private final AuditEventRecorder audit;
  private final BootstrapLoginNotifier notifier;

  public BootstrapAccountLoginListener(AuditEventRecorder audit, BootstrapLoginNotifier notifier) {
    this.audit = audit;
    this.notifier = notifier;
  }

  @Override
  public void onLoginSucceeded(User user, LocalCredentials credentials, Instant now) {
    if (!credentials.isBootstrap()) {
      return;
    }
    UUID pseudonym = audit.pseudonymFor(user.getId(), user.getOrganizationId());
    audit.recordSystemProcessAction(
        AuditEvent.builder()
            .organizationId(user.getOrganizationId())
            .actorRef(LocalRefreshTokenService.SYSTEM_ACTOR)
            .type(AuditEventType.LOCAL_BOOTSTRAP_ACCOUNT_LOGIN)
            .object(AuditObjectType.USER_ACCOUNT, pseudonym, null)
            .subject(AuditSubjectKind.USER, user.getId())
            .outcome(AuditOutcome.SUCCESS)
            .build());
    notifier.bootstrapAccountSignedIn(user, now);
  }

  @Override
  public void onPasswordRejected(User user, LocalCredentials credentials, Instant now) {
    // no event: a failed attempt never reaches the audit trail (Entscheidung 13)
  }
}
