package io.opaa.space;

import io.opaa.library.KnowledgeLibrary;
import io.opaa.permission.AssetType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpaceAssetAssociationRepository
    extends JpaRepository<SpaceAssetAssociation, UUID> {

  List<SpaceAssetAssociation> findBySpaceIdOrderByCreatedAtAsc(UUID spaceId);

  List<SpaceAssetAssociation> findByAssetIdOrderByCreatedAtAsc(UUID assetId);

  Optional<SpaceAssetAssociation> findBySpaceIdAndAssetId(UUID spaceId, UUID assetId);

  boolean existsBySpaceIdAndAssetId(UUID spaceId, UUID assetId);

  /** Every asset id of {@code assetType} associated with {@code spaceId}. */
  @Query(
      "select a.assetId from SpaceAssetAssociation a, Asset s where s.id = a.assetId"
          + " and a.spaceId = :spaceId and s.assetType = :assetType")
  Set<UUID> findAssetIdsBySpaceIdAndAssetType(
      @Param("spaceId") UUID spaceId, @Param("assetType") AssetType assetType);

  /**
   * Every knowledge library associated with {@code spaceId} - the typed view the search reads:
   * {@code ChatService#effectiveLibraryScope} intersects it with the caller's readable libraries
   * for the default @Alles-Wissen scope
   * (docs/features/spaces-and-assets.md#suchbereich-je-chatart). An empty result means "no library
   * association", which the caller treats as "do not narrow", not as "search nothing".
   */
  default Set<UUID> findLibraryIdsBySpaceId(UUID spaceId) {
    return findAssetIdsBySpaceIdAndAssetType(spaceId, KnowledgeLibrary.ASSET_TYPE);
  }

  /**
   * Every association of the given spaces in one query - the overview card's "Quellen" figure
   * (#682) is counted from this in memory, because a plain MEMBER's figure must only include the
   * assets they may read, which no grouped SQL count can express without the caller's readable set.
   */
  List<SpaceAssetAssociation> findBySpaceIdIn(Collection<UUID> spaceIds);
}
