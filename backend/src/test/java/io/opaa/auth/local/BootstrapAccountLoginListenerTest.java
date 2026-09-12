package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.User;
import io.opaa.organization.Organization;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link BootstrapAccountLoginListener} (ADR-0033, Entscheidungen 5 and 13): every successful
 * sign-in with the one bootstrap account - identified by {@code is_bootstrap}, never by its address
 * - is the one audited sign-in ({@code LOCAL_BOOTSTRAP_ACCOUNT_LOGIN}, system actor {@code
 * local-auth}) and is handed to the {@link BootstrapLoginNotifier}; a personal account's sign-in
 * and a rejected password leave nothing behind.
 */
class BootstrapAccountLoginListenerTest {

  private static final Instant NOW = Instant.parse("2026-09-11T09:00:00Z");

  private final AuditEventRecorder audit = mock(AuditEventRecorder.class);
  private final BootstrapLoginNotifier notifier = mock(BootstrapLoginNotifier.class);
  private final BootstrapAccountLoginListener listener =
      new BootstrapAccountLoginListener(audit, notifier);

  private static User admin() {
    User user = User.localAccount("notanker@stadt.example", "Systemverwaltung");
    user.setOrganizationId(Organization.DEFAULT_ID);
    user.setSystemRole(SystemRole.SYSTEM_ADMIN);
    return user;
  }

  @Test
  void theBootstrapAccountsSignInIsAuditedWithoutItsAddressAndReported() {
    User user = admin();
    LocalCredentials row = new LocalCredentials(user.getId(), "Notanker", NOW);
    row.markBootstrap();
    UUID pseudonym = UUID.randomUUID();
    when(audit.pseudonymFor(user.getId(), Organization.DEFAULT_ID)).thenReturn(pseudonym);

    listener.onLoginSucceeded(user, row, NOW);

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).recordSystemProcessAction(captor.capture());
    AuditEvent event = captor.getValue();
    assertThat(event.eventType()).isEqualTo(AuditEventType.LOCAL_BOOTSTRAP_ACCOUNT_LOGIN);
    assertThat(event.actorRef()).isEqualTo(LocalRefreshTokenService.SYSTEM_ACTOR);
    assertThat(event.objectType()).isEqualTo(AuditObjectType.USER_ACCOUNT);
    assertThat(event.objectId()).isEqualTo(pseudonym);
    assertThat(event.objectLabel()).isNull();
    assertThat(event.subjectKind()).isEqualTo(AuditSubjectKind.USER);
    assertThat(event.subjectId()).isEqualTo(user.getId());
    assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
    assertThat(String.valueOf(event.after())).doesNotContain("notanker@stadt.example");
    assertThat(String.valueOf(event.reason())).doesNotContain("notanker@stadt.example");
    verify(notifier).bootstrapAccountSignedIn(user, NOW);
  }

  @Test
  void aPersonalAccountsSignInIsNoEvent() {
    User user = admin();
    LocalCredentials row = new LocalCredentials(user.getId(), "Persönlich", NOW);

    listener.onLoginSucceeded(user, row, NOW);

    verifyNoInteractions(audit, notifier);
  }

  @Test
  void aRejectedPasswordOfTheBootstrapAccountIsNoEventEither() {
    User user = admin();
    LocalCredentials row = new LocalCredentials(user.getId(), "Notanker", NOW);
    row.markBootstrap();

    listener.onPasswordRejected(user, row, NOW);

    verifyNoInteractions(audit, notifier);
    verify(audit, org.mockito.Mockito.never()).recordSystemProcessAction(any());
  }
}
