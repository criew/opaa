package io.opaa.permission;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The state of an account as half-open intervals (ADR-0036 Entscheidung 8): "was this account able
 * to exercise its rights on 3 March" stays answerable after the audit log's own retention period
 * has taken the event away.
 *
 * <p>The database writes the history, not this service: {@code account_state_reconcile} applies the
 * rule of {@code io.opaa.auth.AccountUsability} after every change of {@code users}, {@code
 * local_credentials} and {@code oidc_providers} that can change it, in the transaction of that
 * change, from the boundary clock of ADR-0032 - whichever version or code path writes. What no
 * write announces, an expiry date reached by time, {@link #recordReachedExpiries} records.
 */
@Service
public class AccountStateHistoryService {

  private final AccountStateHistoryRepository repository;

  AccountStateHistoryService(AccountStateHistoryRepository repository) {
    this.repository = repository;
  }

  /**
   * Records the expiry of up to {@code limit} local accounts whose expiry date has passed since the
   * last call; the interval starts at a boundary not before the expiry. Idempotent under concurrent
   * calls: each account is re-evaluated under its own lock.
   *
   * @return how many accounts were re-evaluated
   */
  @Transactional
  public int recordReachedExpiries(int limit) {
    return repository.recordReachedExpiries(limit);
  }

  /**
   * The locked intervals of {@code userIds} overlapping {@code [from, to)} - the one reading path
   * of this history, the Stichtagsauskunft (ADR-0036 Entscheidung 8). Empty input, empty answer.
   */
  @Transactional(readOnly = true)
  public List<AccountStateHistory> lockedIntervalsBetween(
      Collection<UUID> userIds, UUID organizationId, Instant from, Instant to, int limit) {
    return userIds.isEmpty()
        ? List.of()
        : repository.findLockedIntervalsOverlapping(
            userIds, organizationId, from, to, PageRequest.ofSize(limit));
  }
}
