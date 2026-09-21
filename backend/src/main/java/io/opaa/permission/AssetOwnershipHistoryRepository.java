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

public interface AssetOwnershipHistoryRepository
    extends JpaRepository<AssetOwnershipHistory, UUID>, PermissionHistorySweeper {

  @Override
  default String historyTable() {
    return "asset_ownership_history";
  }

  /** This table's part of the retention deletion - see {@link PermissionHistorySweeper}. */
  @Override
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("delete from AssetOwnershipHistory h where h.validTo is not null and h.validTo < :cutoff")
  int deleteClosedIntervalsEndingBefore(@Param("cutoff") Instant cutoff);

  Optional<AssetOwnershipHistory> findByAssetTypeAndAssetIdAndValidToIsNull(
      AssetType assetType, UUID assetId);

  /**
   * Test-only cleanup helper - {@code owner_user_id} is {@code ON DELETE RESTRICT}; see {@link
   * AssetGrantHistoryRepository#deleteBySubjectUserIdIn} for the full reasoning and for why
   * {@code @Transactional} is required on a derived delete declared here.
   */
  @Transactional
  void deleteByOwnerUserIdIn(Collection<UUID> ownerUserIds);
}
