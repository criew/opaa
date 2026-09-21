package io.opaa.group;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupStewardRepository extends JpaRepository<GroupSteward, UUID> {

  List<GroupSteward> findByGroupIdOrderByCreatedAtAsc(UUID groupId);

  /** The stewards of a whole list of groups - one read instead of one per group. */
  List<GroupSteward> findByGroupIdIn(Iterable<UUID> groupIds);

  Optional<GroupSteward> findByGroupIdAndUserId(UUID groupId, UUID userId);

  boolean existsByGroupIdAndUserId(UUID groupId, UUID userId);

  long countByGroupId(UUID groupId);

  /** How many groups the account is responsible for - part of what a handover has to move. */
  long countByUserId(UUID userId);

  @Query("select s.groupId from GroupSteward s where s.userId = :userId")
  Set<UUID> findGroupIdsByUserId(@Param("userId") UUID userId);
}
