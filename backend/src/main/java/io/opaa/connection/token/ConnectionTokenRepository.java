package io.opaa.connection.token;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ConnectionTokenRepository extends JpaRepository<ConnectionToken, UUID> {

  Optional<ConnectionToken> findByConnectedAccountId(UUID connectedAccountId);

  List<ConnectionToken> findByConnectedAccountIdIn(Collection<UUID> connectedAccountIds);

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
