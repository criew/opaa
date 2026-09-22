package io.opaa.group;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupContactRepository extends JpaRepository<GroupContact, UUID> {

  List<GroupContact> findByGroupIdOrderByCreatedAtAsc(UUID groupId);

  /** The contact points of a whole list of groups - one read instead of one per group. */
  List<GroupContact> findByGroupIdIn(Iterable<UUID> groupIds);

  Optional<GroupContact> findByGroupIdAndUserId(UUID groupId, UUID userId);

  boolean existsByGroupIdAndUserId(UUID groupId, UUID userId);

  List<GroupContact> findByUserId(UUID userId);

  @Query("select c.groupId from GroupContact c where c.userId = :userId")
  Set<UUID> findGroupIdsByUserId(@Param("userId") UUID userId);
}
