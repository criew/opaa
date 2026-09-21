package io.opaa.auth;

import io.opaa.permission.PermissionHistorySweeper;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AccountStateHistoryRepository
    extends JpaRepository<AccountStateHistory, UUID>, PermissionHistorySweeper {

  @Override
  default String historyTable() {
    return "account_state_history";
  }

  /**
   * This table's part of the retention deletion - see {@link PermissionHistorySweeper}. Open
   * intervals are never touched, which is also what keeps the <em>current</em> state undeletable:
   * it is always the open one.
   */
  @Override
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("delete from AccountStateHistory h where h.validTo is not null and h.validTo < :cutoff")
  int deleteClosedIntervalsEndingBefore(@Param("cutoff") Instant cutoff);

  Optional<AccountStateHistory> findByUserIdAndValidToIsNull(UUID userId);

  /**
   * The blocked accounts among {@code userIds} - the one question every caller that has to narrow a
   * set of accounts to the usable ones asks. Never called with an empty collection.
   */
  @Query(
      "select h.userId from AccountStateHistory h where h.userId in :userIds"
          + " and h.validTo is null and h.state = io.opaa.auth.AccountState.BLOCKED")
  Set<UUID> findBlockedUserIdsIn(@Param("userIds") Collection<UUID> userIds);

  /**
   * The whole chain of one account, oldest interval first. Only read by tests today; the Stichtag
   * reconstruction that will read it in production is #1822's, together with the authorisation, the
   * time window and the retrieval event ADR-0036, Entscheidung 8 requires of it.
   */
  List<AccountStateHistory> findByUserIdOrderByValidFromAsc(UUID userId);

  /**
   * Test-only cleanup helper - {@code user_id} is {@code ON DELETE RESTRICT}; see {@code
   * io.opaa.permission.AssetGrantHistoryRepository#deleteBySubjectUserIdIn} for the full reasoning
   * and for why {@code @Transactional} is required on a derived delete declared here.
   */
  @Transactional
  void deleteByUserIdIn(Collection<UUID> userIds);
}
