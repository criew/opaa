package io.opaa.permission;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * No read path beyond the open interval the writer itself needs: ADR-0036, Entscheidung 8 rules out
 * a second reading path on the account-state history ("kein Verlauf am Konto neben der
 * Stichtagsauskunft").
 */
public interface AccountStateHistoryRepository
    extends JpaRepository<AccountStateHistory, UUID>, PermissionHistorySweeper {

  @Override
  default String historyTable() {
    return "account_state_history";
  }

  /** This table's part of the retention deletion - see {@link PermissionHistorySweeper}. */
  @Override
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("delete from AccountStateHistory h where h.validTo is not null and h.validTo < :cutoff")
  int deleteClosedIntervalsEndingBefore(@Param("cutoff") Instant cutoff);

  Optional<AccountStateHistory> findByUserIdAndValidToIsNull(UUID userId);

  /**
   * Test-only cleanup helper - {@code user_id} is {@code ON DELETE RESTRICT}; see {@link
   * AssetGrantHistoryRepository#deleteBySubjectUserIdIn} for the full reasoning and for why
   * {@code @Transactional} is required on a derived delete declared here.
   */
  @Transactional
  void deleteByUserIdIn(Collection<UUID> userIds);
}
