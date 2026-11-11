package io.opaa.asset;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The responsibility rows; the period run writes only through the conditional statements here. */
public interface AssetResponsibilityRepository extends JpaRepository<AssetResponsibility, UUID> {

  /**
   * The row under a row lock until the transaction ends: a change, lock or unlock that reads and
   * writes the whole row waits for the period run's conditional statements and for each other, so
   * none of them overwrites a lock or unit the other has just written.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from AssetResponsibility r where r.assetId = :assetId")
  Optional<AssetResponsibility> findByIdForUpdate(@Param("assetId") UUID assetId);

  /** The ids of the given assets that are locked right now. */
  @Query(
      "select r.assetId from AssetResponsibility r where r.assetId in :ids and r.lockedAt is not null")
  List<UUID> findLockedIdsAmong(@Param("ids") Collection<UUID> ids);

  /** One page of all rows, by asset id, for the period run. */
  @Query("select r from AssetResponsibility r where r.assetId > :after order by r.assetId")
  List<AssetResponsibility> findPageAfter(@Param("after") UUID after, Pageable page);

  /** Starts the period; the database clock stamps it, and only once. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          "UPDATE asset_responsibilities SET missing_since = clock_timestamp()"
              + " WHERE asset_id = :assetId AND missing_since IS NULL",
      nativeQuery = true)
  int markMissing(@Param("assetId") UUID assetId);

  /** Ends the period of an object whose unit is back. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          "UPDATE asset_responsibilities SET missing_since = NULL"
              + " WHERE asset_id = :assetId AND missing_since IS NOT NULL",
      nativeQuery = true)
  int clearMissing(@Param("assetId") UUID assetId);

  /**
   * Locks the object for a missing unit when its period started at or before {@code dueBefore} and
   * it is not locked yet; {@code 1} only for the one caller that locked it.
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          "UPDATE asset_responsibilities SET locked_at = clock_timestamp(),"
              + " lock_cause = 'NO_RESPONSIBLE', lock_reason = :reason"
              + " WHERE asset_id = :assetId AND locked_at IS NULL"
              + " AND missing_since IS NOT NULL AND missing_since <= :dueBefore",
      nativeQuery = true)
  int lockForMissingUnit(
      @Param("assetId") UUID assetId,
      @Param("reason") String reason,
      @Param("dueBefore") Instant dueBefore);
}
