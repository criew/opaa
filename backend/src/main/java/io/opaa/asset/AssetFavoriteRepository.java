package io.opaa.asset;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Every method is scoped to one person: a favorite is only ever read or written together with the
 * {@code userId} it belongs to. A plain {@link Repository}, so no inherited {@code findAll} or
 * {@code count} reaches across persons.
 */
public interface AssetFavoriteRepository extends Repository<AssetFavorite, AssetFavorite.Key> {

  /** Idempotent: an asset already marked keeps the time of its first mark. */
  @Modifying
  @Query(
      value =
          "INSERT INTO asset_favorites (asset_id, user_id, organization_id, created_at)"
              + " VALUES (:assetId, :userId, :organizationId, :createdAt)"
              + " ON CONFLICT (asset_id, user_id) DO NOTHING",
      nativeQuery = true)
  void mark(
      @Param("assetId") UUID assetId,
      @Param("userId") UUID userId,
      @Param("organizationId") UUID organizationId,
      @Param("createdAt") Instant createdAt);

  /** Idempotent: removing a mark that does not exist changes nothing. */
  @Modifying
  @Query(
      value = "DELETE FROM asset_favorites WHERE asset_id = :assetId AND user_id = :userId",
      nativeQuery = true)
  void unmark(@Param("assetId") UUID assetId, @Param("userId") UUID userId);
}
