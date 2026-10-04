package io.opaa.connection.account;

import io.opaa.api.types.ConnectedAccountState;
import io.opaa.connection.token.PersonAccounts;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ConnectedAccountRepository extends JpaRepository<ConnectedAccount, UUID>, PersonAccounts {

  Optional<ConnectedAccount> findByUserIdAndProfileId(UUID userId, UUID profileId);

  List<ConnectedAccount> findByUserIdOrderByConnectedAtAsc(UUID userId);

  List<ConnectedAccount> findByProfileIdAndStateNot(UUID profileId, ConnectedAccountState state);

  @Override
  @Query(
      "select a.id as id, a.userId as userId, a.profileId as profileId from ConnectedAccount a"
          + " where a.userId in :userIds and a.profileId in :profileIds")
  List<AccountKey> accountsAmong(
      @Param("userIds") Collection<UUID> userIds, @Param("profileIds") Collection<UUID> profileIds);

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
