package io.opaa.asset;

import io.opaa.permission.AssetType;
import io.opaa.permission.PermissionHistorySweeper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
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
   * This table's part of the retention deletion - see {@link PermissionHistorySweeper}. The third
   * source of the readable-asset formula ages out with the two grant tables, so the reach of the
   * retention period is the same for all three.
   */
  @Override
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("delete from AssetVisibilityHistory h where h.validTo is not null and h.validTo < :cutoff")
  int deleteClosedIntervalsEndingBefore(@Param("cutoff") Instant cutoff);

  Optional<AssetVisibilityHistory> findByAssetTypeAndAssetIdAndValidToIsNull(
      AssetType assetType, UUID assetId);

  /**
   * Every asset of {@code assetType} that was organization-wide at {@code asOf} - the interval's
   * {@code validFrom <= asOf} and ({@code validTo IS NULL OR validTo > asOf}). The Stichtag
   * counterpart of the third way of {@code AssetAccessService#readableAssetIds}.
   */
  @Query(
      "select h.assetId from AssetVisibilityHistory h "
          + "where h.assetType = :assetType and h.organizationId = :organizationId "
          + "and h.visibility = io.opaa.api.types.AssetVisibility.ORGANIZATION "
          + "and h.validFrom <= :asOf and (h.validTo is null or h.validTo > :asOf)")
  Set<UUID> findOrganizationWideAssetIdsAsOf(
      @Param("assetType") AssetType assetType,
      @Param("organizationId") UUID organizationId,
      @Param("asOf") Instant asOf);

  /**
   * Every organization-wide <i>state</i> interval of one asset overlapping {@code [from, to)} - the
   * third source of the Stichtagsauskunft about an asset (#1822). Zero-length event markers are
   * excluded by {@code validTo > validFrom}.
   *
   * <p><b>Scoped to the organization like every other source of that answer.</b> Without it an
   * organization-wide release would disclose the existence and the release periods of another
   * organization's asset. {@code page} bounds what one call reads at all.
   */
  @Query(
      "select h from AssetVisibilityHistory h where h.assetType = :assetType "
          + "and h.assetId = :assetId and h.organizationId = :organizationId "
          + "and h.visibility = io.opaa.api.types.AssetVisibility.ORGANIZATION "
          + "and h.validFrom < :to and (h.validTo is null or h.validTo > :from) "
          + "and (h.validTo is null or h.validTo > h.validFrom)")
  List<AssetVisibilityHistory> findOrganizationWideIntervalsOverlapping(
      @Param("assetType") AssetType assetType,
      @Param("assetId") UUID assetId,
      @Param("organizationId") UUID organizationId,
      @Param("from") Instant from,
      @Param("to") Instant to,
      Pageable page);

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
