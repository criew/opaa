package io.opaa.asset;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The asset shell across every type - what the catalogue-shaped questions (ownership, succession,
 * the reach of a group) ask in one query instead of one per type.
 */
public interface AssetRepository extends JpaRepository<Asset, UUID> {

  /** Whether any asset is still owned by the group; group ids are unique system-wide. */
  boolean existsByOwnerGroupId(UUID ownerGroupId);

  /** The types of the assets one group owns - what a refused group deletion names. */
  @Query(
      value = "SELECT DISTINCT asset_type FROM assets WHERE owner_group_id = :ownerGroupId",
      nativeQuery = true)
  List<String> findTypesOwnedByGroup(@Param("ownerGroupId") UUID ownerGroupId);

  List<Asset> findByOwnerGroupIdAndOrganizationId(UUID ownerGroupId, UUID organizationId);

  List<Asset> findByOwnerUserIdAndOrganizationId(UUID ownerUserId, UUID organizationId);

  long countByOwnerGroupIdAndOrganizationId(UUID ownerGroupId, UUID organizationId);

  long countByOwnerUserIdAndOrganizationId(UUID ownerUserId, UUID organizationId);

  /**
   * How many assets each of the groups owns, in one grouped query - the overview "wo wirkt diese
   * Gruppe" (#1821) asks it for every group at once. A group owning nothing is absent.
   */
  @Query(
      "select a.ownerGroupId as ownerGroupId, count(a) as assetCount from Asset a"
          + " where a.ownerGroupId in :ownerGroupIds and a.organizationId = :organizationId"
          + " group by a.ownerGroupId")
  List<OwnerGroupCount> countByOwnerGroupIdIn(
      @Param("ownerGroupIds") Collection<UUID> ownerGroupIds,
      @Param("organizationId") UUID organizationId);

  interface OwnerGroupCount {
    UUID getOwnerGroupId();

    long getAssetCount();
  }

  /** The shell fields of the given assets, in one query. */
  @Query(
      "select new io.opaa.asset.AssetHeader(a.id, a.assetType, a.organizationId, a.name,"
          + " a.description, a.visibility) from Asset a where a.id in :ids")
  List<AssetHeader> findHeadersByIdIn(@Param("ids") Collection<UUID> ids);

  /** Every asset of one organization - what the succession detection run walks. */
  List<Asset> findByOrganizationId(UUID organizationId);
}
