package io.opaa.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.LocalCredentials;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * A changed password ends every session of the account and says so with {@link
 * LocalAccountSessionsEndedEvent}, so what a taken-over session could have created on the person's
 * behalf - an access token - ends in the same transaction.
 */
class LocalPasswordServiceSessionsEndedTest {

  private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

  private final LocalCredentialsRepository credentials = mock(LocalCredentialsRepository.class);
  private final PasswordEncoder encoder = mock(PasswordEncoder.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final AuditEventRecorder audit = mock(AuditEventRecorder.class);

  private final LocalPasswordService service =
      new LocalPasswordService(
          credentials,
          mock(UserRepository.class),
          mock(LocalActionTokenService.class),
          encoder,
          mock(PasswordPolicy.class),
          mock(LocalTokenRevocationService.class),
          mock(LocalRefreshTokenService.class),
          audit,
          events,
          Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void aChangedPasswordEndsWhatTheSessionsOfTheAccountCreated() {
    User user = User.localAccount("erika@stadt.example", "Erika");
    user.setOrganizationId(UUID.randomUUID());
    LocalCredentials row = new LocalCredentials(user.getId(), "Fachamt", NOW);
    row.setPasswordHash("alt", NOW);
    when(credentials.findById(user.getId())).thenReturn(Optional.of(row));
    when(encoder.matches("aktuell", "alt")).thenReturn(true);
    when(encoder.encode(any())).thenReturn("neu");
    when(audit.pseudonymFor(any(), any())).thenReturn(UUID.randomUUID());

    service.changePassword(user, "aktuell", "ein-ganz-neues-Passwort");

    ArgumentCaptor<LocalAccountSessionsEndedEvent> published =
        ArgumentCaptor.forClass(LocalAccountSessionsEndedEvent.class);
    verify(events).publishEvent(published.capture());
    assertThat(published.getValue().user()).isSameAs(user);
    assertThat(published.getValue().cause()).isEqualTo(RevocationReason.PASSWORD_CHANGED);
    assertThat(published.getValue().actorUserId()).isEqualTo(user.getId());
  }
}
