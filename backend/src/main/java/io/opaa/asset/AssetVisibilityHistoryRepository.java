package io.opaa.asset;

import io.opaa.permission.AssetType;
import io.opaa.permission.PermissionHistorySweeper;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssetVisibilityHistoryRepository
    extends JpaRepository<AssetVisibilityHistory, UUID>, PermissionHistorySweeper {

  @Override
  default String historyTable() {
    return "asset_visibility_history";
  }

  /**
   * This table's part of the retention deletion - see {@link PermissionHistorySweeper}. Findability
   * and the Fremdzugang age out with the grant tables, so the reach of the retention period is the
   * same for every reach statement.
   */
  @Override
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("delete from AssetVisibilityHistory h where h.validTo is not null and h.validTo < :cutoff")
  int deleteClosedIntervalsEndingBefore(@Param("cutoff") Instant cutoff);

  Optional<AssetVisibilityHistory> findByAssetTypeAndAssetIdAndValidToIsNull(
      AssetType assetType, UUID assetId);

  /**
   * The one state interval of the asset covering {@code asOf}. Zero-length event markers never
   * satisfy {@code validTo > asOf} together with {@code validFrom <= asOf}.
   */
  @Query(
      "select h from AssetVisibilityHistory h where h.assetType = :assetType "
          + "and h.assetId = :assetId "
          + "and h.validFrom <= :asOf and (h.validTo is null or h.validTo > :asOf)")
  Optional<AssetVisibilityHistory> findStateAsOf(
      @Param("assetType") AssetType assetType,
      @Param("assetId") UUID assetId,
      @Param("asOf") Instant asOf);
}
