package io.opaa.connection.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.account.LocalAccountAccessEndedEvent;
import io.opaa.auth.AccountUsability;
import io.opaa.auth.AccountUsability.State;
import io.opaa.auth.OidcProvidersChangedEvent;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.connection.token.ConnectionLifecycleProperties;
import io.opaa.connection.token.ConnectionSecrets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The reconciliation never hands a failure back to the act it follows, which is saved already, and
 * leaves a person in a passing state - a failed-login lockout, an open invitation - as recorded.
 */
class ConnectionLifecycleReconcilerTest {

  private final ConnectionPersonStateRepository states =
      mock(ConnectionPersonStateRepository.class);
  private final ConnectedAccountService accounts = mock(ConnectedAccountService.class);
  private final UserRepository users = mock(UserRepository.class);
  private final AccountUsability usability = mock(AccountUsability.class);
  private final AccountUsability.Snapshot snapshot = mock(AccountUsability.Snapshot.class);
  private final ConnectionLifecycleReconciler reconciler =
      new ConnectionLifecycleReconciler(
          states,
          accounts,
          mock(ConnectionSecrets.class),
          users,
          usability,
          ConnectionLifecycleProperties.defaults(),
          mock(PlatformTransactionManager.class),
          Clock.systemUTC());

  @Test
  void aFailingReconciliationAfterAProviderChangeLeavesTheChangeSuccessful() {
    when(states.findPersonsConcerned())
        .thenThrow(new DataAccessResourceFailureException("database gone"));

    assertThatCode(() -> reconciler.onProvidersChanged(new OidcProvidersChangedEvent()))
        .doesNotThrowAnyException();
  }

  @Test
  void aFailingReconciliationAfterAnEndedAccessLeavesTheActSuccessful() {
    when(states.findPersonsConcernedAmong(any()))
        .thenThrow(new DataAccessResourceFailureException("database gone"));
    User user = mock(User.class);
    when(user.getId()).thenReturn(UUID.randomUUID());

    assertThatCode(
            () -> reconciler.onAccessEnded(LocalAccountAccessEndedEvent.bySystem(user, "test")))
        .doesNotThrowAnyException();
  }

  @Test
  void aPassingLockoutOrInvitationLeavesTheRecordedStateAsItIs() {
    for (State passing : List.of(State.LOCKED_OUT, State.INVITED)) {
      UUID id = UUID.randomUUID();
      User user = mock(User.class);
      when(user.getId()).thenReturn(id);
      ConnectionPersonState resting = new ConnectionPersonState(id);
      Instant since = Clock.systemUTC().instant();
      resting.dormant(since);
      when(states.findPersonsConcernedAmong(List.of(id))).thenReturn(List.of(id));
      when(states.findById(id)).thenReturn(Optional.of(resting));
      when(users.findAllById(List.of(id))).thenReturn(List.of(user));
      when(usability.snapshot()).thenReturn(snapshot);
      when(snapshot.withInactivityThreshold(any())).thenReturn(snapshot);
      when(snapshot.statesOf(any())).thenReturn(Map.of(id, passing));

      reconciler.reconciled(List.of(id));

      assertThat(resting.getDormantSince()).isEqualTo(since);
      verify(states, never()).save(resting);
      verify(accounts, never()).endAllOf(any(), any(), any());
    }
  }
}
