package io.opaa.permission;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
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
   * Every ownership interval of one asset that overlaps {@code [from, to)} - the ownership half of
   * the Stichtagsauskunft. The zero-length deletion marker is excluded by {@code validTo >
   * validFrom}; the interval it closes is in the result already.
   */
  @Query(
      "select h from AssetOwnershipHistory h "
          + "where h.assetType = :assetType and h.assetId = :assetId "
          + "and h.organizationId = :organizationId "
          + "and h.validFrom < :to and (h.validTo is null or h.validTo > :from) "
          + "and (h.validTo is null or h.validTo > h.validFrom)")
  List<AssetOwnershipHistory> findAssetIntervalsOverlapping(
      @Param("assetType") AssetType assetType,
      @Param("assetId") UUID assetId,
      @Param("organizationId") UUID organizationId,
      @Param("from") Instant from,
      @Param("to") Instant to,
      Pageable page);

  /**
   * Test-only cleanup helper - {@code owner_user_id} is {@code ON DELETE RESTRICT}; see {@link
   * AssetGrantHistoryRepository#deleteBySubjectUserIdIn} for the full reasoning and for why
   * {@code @Transactional} is required on a derived delete declared here.
   */
  @Transactional
  void deleteByOwnerUserIdIn(Collection<UUID> ownerUserIds);
}
