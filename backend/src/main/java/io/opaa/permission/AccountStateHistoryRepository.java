package io.opaa.permission;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Stichtagsauskunft is the only reading path (ADR-0036, Entscheidung 8 - "kein Verlauf am Konto
 * neben der Stichtagsauskunft"); the database writes the rows. {@code HistoryReadPathTest} keeps it
 * that way.
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

  /**
   * The {@link AccountState#LOCKED} intervals of {@code userIds} that overlap {@code [from, to)}, a
   * lockout up to the end it reached by itself. Asked with the persons an answer already names,
   * never for an organization as a whole.
   */
  @Query(
      "select h from AccountStateHistory h "
          + "where h.userId in :userIds and h.organizationId = :organizationId "
          + "and h.state = io.opaa.permission.AccountState.LOCKED "
          + "and h.validFrom < :to and (h.validTo is null or h.validTo > :from) "
          + "and (h.lockedUntil is null or h.lockedUntil > :from) "
          + "and (h.validTo is null or h.validTo > h.validFrom)")
  List<AccountStateHistory> findLockedIntervalsOverlapping(
      @Param("userIds") Collection<UUID> userIds,
      @Param("organizationId") UUID organizationId,
      @Param("from") Instant from,
      @Param("to") Instant to,
      Pageable page);

  /** The database function behind {@link AccountStateHistoryService#recordReachedExpiries}. */
  @Query(value = "SELECT account_state_record_reached_expiries(:limit)", nativeQuery = true)
  int recordReachedExpiries(@Param("limit") int limit);

  /**
   * Test-only cleanup helper for rows a test wrote itself; see {@link
   * AssetGrantHistoryRepository#deleteBySubjectUserIdIn} for why {@code @Transactional} is required
   * on a derived delete declared here.
   */
  @Transactional
  void deleteByUserIdIn(Collection<UUID> userIds);
}
