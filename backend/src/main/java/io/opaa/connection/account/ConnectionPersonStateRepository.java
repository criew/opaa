package io.opaa.connection.account;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ConnectionPersonStateRepository extends JpaRepository<ConnectionPersonState, UUID> {

  /**
   * The persons the lifecycle concerns: with a connected account in any state, with a private
   * library, or with a recorded state.
   */
  @Query(
      value =
          "SELECT user_id FROM connected_accounts"
              + " UNION SELECT owner_user_id FROM assets WHERE owner_only"
              + " AND owner_user_id IS NOT NULL"
              + " UNION SELECT user_id FROM connection_person_states",
      nativeQuery = true)
  List<UUID> findPersonsConcerned();

  /** {@link #findPersonsConcerned} among {@code userIds}. */
  @Query(
      value =
          "SELECT user_id FROM connected_accounts WHERE user_id IN (:userIds)"
              + " UNION SELECT owner_user_id FROM assets WHERE owner_only"
              + " AND owner_user_id IN (:userIds)"
              + " UNION SELECT user_id FROM connection_person_states WHERE user_id IN (:userIds)",
      nativeQuery = true)
  List<UUID> findPersonsConcernedAmong(@Param("userIds") Collection<UUID> userIds);

  @Query(
      "select s from ConnectionPersonState s where s.deactivatedSince is not null"
          + " and s.deactivatedSince < :before")
  List<ConnectionPersonState> findDeactivatedBefore(@Param("before") Instant before);
}
