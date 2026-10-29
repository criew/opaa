package io.opaa.externalaccess.token;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.opaa.account.LocalAccountSessionsEndedEvent;
import io.opaa.account.LocalRefreshTokenService;
import io.opaa.account.RevocationReason;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The end of every session of an account - password change or reset, refresh replay - ends its
 * access tokens too, in the name of the person who acted or, for a system process, of none.
 */
class ExternalAccessTokenSessionsEndedListenerTest {

  private final ExternalAccessTokenLapseService lapses =
      mock(ExternalAccessTokenLapseService.class);
  private final ExternalAccessTokenAccountLifecycleListener listener =
      new ExternalAccessTokenAccountLifecycleListener(lapses, mock(UserRepository.class));

  private final User user = User.localAccount("erika@stadt.example", "Erika");

  @Test
  void aPasswordChangeEndsTheTokensOfTheAccount() {
    listener.onSessionsEnded(
        LocalAccountSessionsEndedEvent.by(user, RevocationReason.PASSWORD_CHANGED, user.getId()));

    verify(lapses).endTokensOfAccount(user, user.getId());
  }

  @Test
  void anAdministratorsResetEndsTheTokensInTheAdministratorsName() {
    UUID admin = UUID.randomUUID();

    listener.onSessionsEnded(
        LocalAccountSessionsEndedEvent.by(user, RevocationReason.ADMIN_RESET, admin));

    verify(lapses).endTokensOfAccount(user, admin);
  }

  @Test
  void aDetectedReplayEndsTheTokensAsASystemAct() {
    listener.onSessionsEnded(
        LocalAccountSessionsEndedEvent.bySystem(
            user, RevocationReason.REUSE_DETECTED, LocalRefreshTokenService.SYSTEM_ACTOR));

    verify(lapses).endTokensOfAccount(user, null);
  }
}
