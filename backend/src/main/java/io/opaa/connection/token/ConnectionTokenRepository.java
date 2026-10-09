package io.opaa.connection.token;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ConnectionTokenRepository extends JpaRepository<ConnectionToken, UUID> {

  Optional<ConnectionToken> findByConnectedAccountId(UUID connectedAccountId);

  /** The row, locked against a parallel renewal until the transaction ends. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from ConnectionToken t where t.id = :id")
  Optional<ConnectionToken> findLockedById(@Param("id") UUID id);

  /**
   * The row of {@code connectedAccountId}, locked before it is discarded: a renewal running at the
   * same time commits first, and the discard revokes the tokens it left.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from ConnectionToken t where t.connectedAccountId = :accountId")
  Optional<ConnectionToken> findLockedByConnectedAccountId(@Param("accountId") UUID accountId);

  /**
   * The persons' OAuth grants under {@code profileId}, locked before they are deleted, as {@link
   * #findLockedByConnectedAccountId}.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select t from ConnectionToken t where t.profileId = :profileId"
          + " and t.connectedAccountId is not null"
          + " and t.kind = io.opaa.connection.token.ConnectionToken.Kind.OAUTH")
  List<ConnectionToken> findPersonGrantsUnder(@Param("profileId") UUID profileId);

  List<ConnectionToken> findByConnectedAccountIdIn(Collection<UUID> connectedAccountIds);

  /**
   * The persons' OAuth grants whose named end lies after {@code now} and not after {@code horizon}
   * and was not warned of yet, locked so that a parallel run waits and then skips them.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select t from ConnectionToken t where t.connectedAccountId is not null"
          + " and t.kind = io.opaa.connection.token.ConnectionToken.Kind.OAUTH"
          + " and t.expiresAt > :now and t.expiresAt <= :horizon and t.expiryWarnedAt is null")
  List<ConnectionToken> findGrantsEndingUnwarned(
      @Param("now") Instant now, @Param("horizon") Instant horizon);

  /** Deletes the secret of one connected account; returns how many rows went. */
  @Modifying(flushAutomatically = true)
  @Query("delete from ConnectionToken t where t.connectedAccountId = :accountId")
  int deleteByAccount(@Param("accountId") UUID accountId);

  /** The persons' secrets whose end, named by the provider or set by a rejection, is past. */
  @Query(
      "select count(t) from ConnectionToken t where t.connectedAccountId is not null"
          + " and t.expiresAt <= :now")
  long countPersonsEndedBy(@Param("now") Instant now);

  Optional<ConnectionToken> findByLibraryId(UUID libraryId);

  List<ConnectionToken> findByLibraryIdIn(Collection<UUID> libraryIds);

  /**
   * The rows of libraries' own consents and pending consents under {@code profileId}, locked before
   * they are deleted, as {@link #findLockedByConnectedAccountId}.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select t from ConnectionToken t where t.profileId = :profileId"
          + " and (t.libraryId is not null or t.pendingUserId is not null)")
  List<ConnectionToken> findConsentsUnder(@Param("profileId") UUID profileId);

  /** Deletes every library's own consent and every pending consent under {@code profileId}. */
  @Modifying(flushAutomatically = true)
  @Query(
      "delete from ConnectionToken t where t.profileId = :profileId"
          + " and (t.libraryId is not null or t.pendingUserId is not null)")
  int deleteConsentsUnder(@Param("profileId") UUID profileId);

  /** The pending consents expired by {@code now}, locked so a parallel sweep skips them. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from ConnectionToken t where t.pendingExpiresAt <= :now")
  List<ConnectionToken> findPendingExpiredBy(@Param("now") Instant now);

  /**
   * The libraries' OAuth grants whose named end lies after {@code now} and not after {@code
   * horizon} and was not warned of yet, locked as {@link #findGrantsEndingUnwarned}.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select t from ConnectionToken t where t.libraryId is not null"
          + " and t.kind = io.opaa.connection.token.ConnectionToken.Kind.OAUTH"
          + " and t.expiresAt > :now and t.expiresAt <= :horizon and t.expiryWarnedAt is null")
  List<ConnectionToken> findLibraryGrantsEndingUnwarned(
      @Param("now") Instant now, @Param("horizon") Instant horizon);

  /** Deletes every person's secret under {@code profileId} in one statement. */
  @Modifying(flushAutomatically = true)
  @Query(
      "delete from ConnectionToken t where t.profileId = :profileId"
          + " and t.connectedAccountId is not null")
  int deletePersonsUnder(@Param("profileId") UUID profileId);
}
