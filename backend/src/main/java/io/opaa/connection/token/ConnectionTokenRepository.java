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

  /** The persons' OAuth grants under {@code profileId}, read before they are deleted. */
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

  /** Deletes every person's secret under {@code profileId} in one statement. */
  @Modifying(flushAutomatically = true)
  @Query(
      "delete from ConnectionToken t where t.profileId = :profileId"
          + " and t.connectedAccountId is not null")
  int deletePersonsUnder(@Param("profileId") UUID profileId);
}
