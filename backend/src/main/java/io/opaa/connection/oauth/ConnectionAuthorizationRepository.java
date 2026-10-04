package io.opaa.connection.oauth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ConnectionAuthorizationRepository extends JpaRepository<ConnectionAuthorization, UUID> {

  /**
   * Namespace of {@link #lockStartsOf}'s advisory locks - registered in the list at {@code
   * AssetGrantRepository#ASSET_GRANT_MUTATION_LOCK_NAMESPACE}.
   */
  int AUTHORIZATION_START_LOCK_NAMESPACE = 207;

  /**
   * Serializes the starts of one person for the rest of the transaction: the budget counts only
   * what no parallel start is about to add.
   */
  @Query(
      value =
          "SELECT 1 FROM (SELECT pg_advisory_xact_lock("
              + AUTHORIZATION_START_LOCK_NAMESPACE
              + ", hashtext(CAST(:userId AS text)))) acquired",
      nativeQuery = true)
  int lockStartsOf(@Param("userId") UUID userId);

  Optional<ConnectionAuthorization> findByStateHash(String stateHash);

  /** The starts of {@code userId} since {@code since}, oldest first. */
  List<ConnectionAuthorization> findByUserIdAndCreatedAtAfterOrderByCreatedAtAsc(
      UUID userId, Instant since);

  /**
   * Uses the authorization {@code id} up at {@code now} unless it is used up or expired already;
   * returns the rows written, 0 or 1. The one completion a state allows.
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update ConnectionAuthorization a set a.consumedAt = :now"
          + " where a.id = :id and a.consumedAt is null and a.expiresAt > :now")
  int consume(@Param("id") UUID id, @Param("now") Instant now);

  /** Deletes every authorization that ended before {@code before}; returns how many went. */
  @Modifying(flushAutomatically = true)
  @Query("delete from ConnectionAuthorization a where a.expiresAt < :before")
  int deleteEndedBefore(@Param("before") Instant before);
}
