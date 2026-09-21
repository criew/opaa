package io.opaa.permission;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Records the state of an account as half-open intervals (#1818, ADR-0036 Entscheidung 8): "was
 * this account able to exercise its rights on 3 March" stays answerable after the audit log's own
 * retention period has taken the event away, and a lock from the directory synchronisation is
 * reversible without leaving a break in the chain.
 *
 * <p>Follows the interval contract of {@link PermissionHistoryService} and shares its {@link
 * PermissionHistoryClock}. The chain of an account starts with its first state change, not with its
 * creation: {@link #recordLocked} writes the closed {@code ACTIVE} interval from {@code createdAt}
 * along with the {@code LOCKED} one, so the chain is complete from the account's creation without a
 * row per account making every account undeletable (see changelog 064).
 *
 * <p>Every method runs in the caller's own transaction, so the lock and its interval commit or roll
 * back together.
 */
@Service
public class AccountStateHistoryService {

  private final AccountStateHistoryRepository repository;
  private final PermissionHistoryClock clock;

  AccountStateHistoryService(
      AccountStateHistoryRepository repository, PermissionHistoryClock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  /**
   * Closes the open interval, if the account already has one, and opens the {@code LOCKED} one. For
   * an account whose state changes for the first time, the {@code ACTIVE} interval it was in since
   * {@code createdAt} is written and closed in the same call.
   */
  public void recordLocked(UUID userId, UUID organizationId, Instant createdAt) {
    Instant now = clock.nextBoundary();
    if (!closeOpenInterval(userId, now)) {
      AccountStateHistory sinceCreation =
          new AccountStateHistory(
              userId,
              organizationId,
              AccountState.ACTIVE,
              AccountStateHistoryCause.ACCOUNT_CREATED,
              // An account created within the same microsecond as its lock would otherwise open
              // an interval that does not precede the one closing it.
              createdAt.isBefore(now) ? createdAt : now);
      sinceCreation.close(now);
      repository.save(sinceCreation);
    }
    repository.save(
        new AccountStateHistory(
            userId,
            organizationId,
            AccountState.LOCKED,
            AccountStateHistoryCause.DIRECTORY_LOCKED,
            now));
  }

  /** Closes the {@code LOCKED} interval and opens the {@code ACTIVE} one that follows it. */
  public void recordUnlocked(UUID userId, UUID organizationId) {
    Instant now = clock.nextBoundary();
    closeOpenInterval(userId, now);
    repository.save(
        new AccountStateHistory(
            userId,
            organizationId,
            AccountState.ACTIVE,
            AccountStateHistoryCause.DIRECTORY_UNLOCKED,
            now));
  }

  /**
   * Flushes immediately for the same reason {@code PermissionHistoryService#closeOpenGrantInterval}
   * does: Hibernate orders every queued insert before every queued update, so without the flush the
   * next interval's {@code INSERT} would reach Postgres before this {@code UPDATE ... SET valid_to}
   * and transiently violate {@code uk_account_state_history_open}.
   *
   * @return whether an open interval was found and closed
   */
  private boolean closeOpenInterval(UUID userId, Instant now) {
    return repository
        .findByUserIdAndValidToIsNull(userId)
        .map(
            interval -> {
              interval.close(now);
              repository.saveAndFlush(interval);
              return true;
            })
        .orElse(false);
  }
}
