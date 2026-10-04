package io.opaa.connection.account;

import io.opaa.account.LocalAccountAccessEndedEvent;
import io.opaa.api.types.ConnectionEndCause;
import io.opaa.auth.AccountUsability;
import io.opaa.auth.OidcProvidersChangedEvent;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.connection.account.ConnectedAccountService.Ended;
import io.opaa.connection.log.ConnectionLogActor;
import io.opaa.connection.token.ConnectionLifecycleProperties;
import io.opaa.connection.token.ConnectionSecrets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Aligns persons' connections with whether their account is usable (ADR-0041, Entscheidung 4): a
 * deactivated account ends every connection and deletes its secrets at once and records since when;
 * a resting one only records since when; a usable one clears both. Only {@code DEACTIVATED} ends
 * anything - a handover or a changed group does not. Runs daily, after the commit of an event that
 * may have changed usability, and at start, so a restored backup is aligned too. Each person is
 * reconciled in a transaction of their own; its log names numbers, never a person.
 */
@Component
public class ConnectionLifecycleReconciler {

  private static final Logger log = LoggerFactory.getLogger(ConnectionLifecycleReconciler.class);
  private static final int BATCH_SIZE = 200;

  private final ConnectionPersonStateRepository states;
  private final ConnectedAccountService accounts;
  private final ConnectionSecrets secrets;
  private final UserRepository users;
  private final AccountUsability usability;
  private final ConnectionLifecycleProperties lifecycle;
  private final TransactionTemplate perPerson;
  private final Clock clock;

  ConnectionLifecycleReconciler(
      ConnectionPersonStateRepository states,
      ConnectedAccountService accounts,
      ConnectionSecrets secrets,
      UserRepository users,
      AccountUsability usability,
      ConnectionLifecycleProperties lifecycle,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.states = states;
    this.accounts = accounts;
    this.secrets = secrets;
    this.users = users;
    this.usability = usability;
    this.lifecycle = lifecycle;
    // its own transaction even where called after a commit, whose resources are still bound
    this.perPerson = new TransactionTemplate(transactionManager);
    this.perPerson.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.clock = clock;
  }

  /** Reconciles the persons among {@code userIds} the lifecycle concerns. */
  public void reconcile(Collection<UUID> userIds) {
    reconciled(userIds);
  }

  /**
   * Reconciles every person the lifecycle concerns: with a connected account, a private library or
   * a recorded state.
   */
  public void reconcileAll() {
    reconciledAll();
  }

  /**
   * After the commit of the act that ended the access; a failure is logged and left to the daily
   * run, never handed back to the act, which is saved already.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onAccessEnded(LocalAccountAccessEndedEvent event) {
    guarded("after an ended access", () -> reconciled(List.of(event.user().getId())));
  }

  /** Carries no provider, so every person is reconciled; failures as in {@link #onAccessEnded}. */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onProvidersChanged(OidcProvidersChangedEvent event) {
    guarded("after a change of the sign-in providers", this::reconciledAll);
  }

  @Scheduled(cron = "0 15 4 * * *")
  public void daily() {
    logged("daily", reconciledAll());
  }

  /** After a restored backup: secrets of deactivated accounts go, expired ones are counted. */
  @EventListener(ApplicationReadyEvent.class)
  public void afterStart() {
    Outcome outcome = reconciledAll();
    log.info(
        "Connection lifecycle after start: {} secret(s) of deactivated accounts deleted, {}"
            + " expired secret(s) of persons counted, {} person(s) failed",
        outcome.secretsDeleted(),
        secrets.countExpiredPersonSecrets(),
        outcome.failed());
  }

  Outcome reconciled(Collection<UUID> userIds) {
    if (userIds.isEmpty()) {
      return Outcome.NONE;
    }
    return apply(states.findPersonsConcernedAmong(userIds));
  }

  Outcome reconciledAll() {
    return apply(states.findPersonsConcerned());
  }

  private static void guarded(String occasion, Supplier<Outcome> run) {
    try {
      logged(occasion, run.get());
    } catch (RuntimeException e) {
      log.warn(
          "Connection lifecycle {} failed ({}); the daily run catches up",
          occasion,
          e.getClass().getSimpleName());
    }
  }

  private Outcome apply(List<UUID> userIds) {
    Instant now = clock.instant();
    AccountUsability.Snapshot snapshot =
        usability.snapshot().withInactivityThreshold(lifecycle.inactivityThreshold());
    Outcome outcome = Outcome.NONE;
    for (int from = 0; from < userIds.size(); from += BATCH_SIZE) {
      List<User> found =
          users.findAllById(
              new ArrayList<>(userIds.subList(from, Math.min(from + BATCH_SIZE, userIds.size()))));
      Map<UUID, AccountUsability.State> stateOf = snapshot.statesOf(found);
      for (User user : found) {
        outcome = outcome.plus(applyTo(user.getId(), stateOf.get(user.getId()), now));
      }
    }
    return outcome;
  }

  /** One person in a transaction of their own; a failure leaves them to the next run. */
  private Outcome applyTo(UUID userId, AccountUsability.State state, Instant now) {
    try {
      return perPerson.execute(
          status -> {
            ConnectionPersonState row =
                states.findById(userId).orElseGet(() -> new ConnectionPersonState(userId));
            Outcome result;
            if (state.isDeactivated()) {
              Ended ended =
                  accounts.endAllOf(
                      userId, ConnectionEndCause.ACCOUNT_DEACTIVATED, ConnectionLogActor.system());
              row.deactivated(now);
              result = new Outcome(1, 0, ended.connections(), ended.secrets(), 0);
            } else if (state.isDormant()) {
              row.dormant(now);
              result = new Outcome(0, 1, 0, 0, 0);
            } else if (state.isUsable()) {
              row.usable(now);
              result = Outcome.NONE;
            } else {
              // a failed-login lockout or an open invitation passes: the row stays as it is
              return Outcome.NONE;
            }
            states.save(row);
            return result;
          });
    } catch (RuntimeException e) {
      log.warn(
          "Connection lifecycle: a person could not be reconciled ({}); the next run retries",
          e.getClass().getSimpleName());
      return new Outcome(0, 0, 0, 0, 1);
    }
  }

  private static void logged(String occasion, Outcome outcome) {
    log.info(
        "Connection lifecycle {}: {} person(s) deactivated, {} resting; {} connection(s) ended,"
            + " {} secret(s) deleted, {} person(s) failed",
        occasion,
        outcome.deactivated(),
        outcome.dormant(),
        outcome.connectionsEnded(),
        outcome.secretsDeleted(),
        outcome.failed());
  }

  /** What one reconciliation found and did, as numbers only; for the log only. */
  record Outcome(
      int deactivated, int dormant, int connectionsEnded, int secretsDeleted, int failed) {

    static final Outcome NONE = new Outcome(0, 0, 0, 0, 0);

    Outcome plus(Outcome other) {
      return new Outcome(
          deactivated + other.deactivated,
          dormant + other.dormant,
          connectionsEnded + other.connectionsEnded,
          secretsDeleted + other.secretsDeleted,
          failed + other.failed);
    }
  }
}
