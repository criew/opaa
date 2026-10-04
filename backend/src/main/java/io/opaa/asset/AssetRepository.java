package io.opaa.asset;

import io.opaa.permission.AssetType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

  /** A person's transferable assets - an owner-only one never changes hands. */
  List<Asset> findByOwnerUserIdAndOrganizationIdAndOwnerOnlyFalse(
      UUID ownerUserId, UUID organizationId);

  long countByOwnerGroupIdAndOrganizationId(UUID ownerGroupId, UUID organizationId);

  /** The count of {@link #findByOwnerUserIdAndOrganizationIdAndOwnerOnlyFalse}. */
  long countByOwnerUserIdAndOrganizationIdAndOwnerOnlyFalse(UUID ownerUserId, UUID organizationId);

  /** Those of {@code ids} that are owner-only assets of one type in an organization. */
  @Query(
      "select a.id from Asset a where a.assetType = :assetType"
          + " and a.organizationId = :organizationId and a.ownerOnly = true and a.id in :ids")
  Set<UUID> findOwnerOnlyIdsAmong(
      @Param("assetType") AssetType assetType,
      @Param("organizationId") UUID organizationId,
      @Param("ids") Collection<UUID> ids);

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
          + " a.description) from Asset a where a.id in :ids")
  List<AssetHeader> findHeadersByIdIn(@Param("ids") Collection<UUID> ids);

  /**
   * The catalog in one query over every type: the assets of {@code assetTypes} in the organization
   * that are among {@code readableIds}, whose name or description matches {@code pattern}
   * case-insensitively (a {@code LIKE} pattern escaped with a backslash). The favorites of {@code
   * userId} come first, then by name; with {@code favoritesOnly} nothing else is selected.
   */
  @Query(
      value =
          CATALOG_SELECT
              + CATALOG_CONDITION
              + " order by "
              + FAVORITES_FIRST
              + ", lower(a.name), a.id",
      countQuery = "select count(a)" + CATALOG_CONDITION)
  Page<AssetCatalogRow> findCatalogPage(
      @Param("organizationId") UUID organizationId,
      @Param("assetTypes") Collection<AssetType> assetTypes,
      @Param("readableIds") Set<UUID> readableIds,
      @Param("pattern") String pattern,
      @Param("userId") UUID userId,
      @Param("favoritesOnly") boolean favoritesOnly,
      Pageable pageable);

  /**
   * Marks a content change written outside the asset entity - a document uploaded, deleted or
   * indexed. Touches only {@code updated_at} and never moves it backwards, so neither a concurrent
   * edit of the asset nor an earlier change committing late is overwritten.
   */
  @Modifying
  @Transactional
  @Query(
      value = "UPDATE assets SET updated_at = :at WHERE id = :assetId AND updated_at < :at",
      nativeQuery = true)
  int markContentChanged(@Param("assetId") UUID assetId, @Param("at") Instant at);

  /**
   * In how many spaces each of the assets is associated, in one grouped query - the spread the
   * catalog shows. An asset without association is absent.
   */
  @Query(
      value =
          "SELECT asset_id AS \"assetId\", count(*) AS \"spaceCount\""
              + " FROM space_asset_associations WHERE asset_id IN (:assetIds) GROUP BY asset_id",
      nativeQuery = true)
  List<AssetSpaceCount> countSpaceAssociations(@Param("assetIds") Collection<UUID> assetIds);

  interface AssetSpaceCount {
    UUID getAssetId();

    long getSpaceCount();
  }

  String CATALOG_SELECT =
      "select a.id as id, a.assetType as assetType, a.name as name,"
          + " a.description as description, a.ownerType as ownerType,"
          + " a.ownerUserId as ownerUserId, a.ownerGroupId as ownerGroupId,"
          + " a.ownerOnly as ownerOnly, a.origin as origin, a.updatedAt as updatedAt";

  String CATALOG_CONDITION =
      " from Asset a left join AssetFavorite f on f.assetId = a.id and f.userId = :userId"
          + " where a.organizationId = :organizationId"
          + " and a.assetType in :assetTypes"
          + " and a.id in :readableIds"
          + " and (lower(a.name) like lower(:pattern) escape '\\'"
          + " or lower(coalesce(a.description, '')) like lower(:pattern) escape '\\')"
          + " and (:favoritesOnly = false or f.assetId is not null)";

  /** The caller's own favorites before everything else; the join names only their marks. */
  String FAVORITES_FIRST = "case when f.assetId is null then 1 else 0 end";

  /** Every asset of one organization - what the succession detection run walks. */
  List<Asset> findByOrganizationId(UUID organizationId);
}
