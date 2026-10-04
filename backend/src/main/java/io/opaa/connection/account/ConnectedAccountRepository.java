package io.opaa.connection.account;

import io.opaa.api.types.ConnectedAccountState;
import io.opaa.connection.token.PersonAccounts;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ConnectedAccountRepository extends JpaRepository<ConnectedAccount, UUID>, PersonAccounts {

  Optional<ConnectedAccount> findByUserIdAndProfileId(UUID userId, UUID profileId);

  List<ConnectedAccount> findByUserIdOrderByConnectedAtAsc(UUID userId);

  List<ConnectedAccount> findByProfileIdAndStateNot(UUID profileId, ConnectedAccountState state);

  List<ConnectedAccount> findByUserIdAndStateNot(UUID userId, ConnectedAccountState state);

  @Override
  @Query(
      "select a.id as id, a.userId as userId, a.profileId as profileId,"
          + " a.lastUsedAt as lastUsedAt from ConnectedAccount a"
          + " where a.userId in :userIds and a.profileId in :profileIds")
  List<AccountKey> accountsAmong(
      @Param("userIds") Collection<UUID> userIds, @Param("profileIds") Collection<UUID> profileIds);

  /** Skips a row another transaction holds, so a hand-out never waits for the bookkeeping. */
  @Override
  @Modifying
  @Query(
      value =
          "UPDATE connected_accounts SET last_used_at = :now WHERE id = (SELECT id FROM"
              + " connected_accounts WHERE id = :id AND (last_used_at IS NULL OR last_used_at <"
              + " :notBefore) FOR UPDATE SKIP LOCKED)",
      nativeQuery = true)
  int markUsed(
      @Param("id") UUID accountId,
      @Param("now") Instant now,
      @Param("notBefore") Instant notBefore);

  /** The connections per profile and state among {@code profileIds}, in one query. */
  @Query(
      "select a.profileId as profileId, a.state as state, count(a) as connections"
          + " from ConnectedAccount a where a.profileId in :profileIds"
          + " group by a.profileId, a.state")
  List<StateCount> countByProfileAndState(@Param("profileIds") Collection<UUID> profileIds);

  /** One row of {@link #countByProfileAndState}. */
  interface StateCount {
    UUID getProfileId();

    ConnectedAccountState getState();

    long getConnections();
  }
}
