package io.opaa.group;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupMembershipRepository extends JpaRepository<GroupMembership, UUID> {

  List<GroupMembership> findByGroupId(UUID groupId);

  Optional<GroupMembership> findByUserIdAndGroupId(UUID userId, UUID groupId);

  @Query("select m.group.id from GroupMembership m where m.userId = :userId")
  Set<UUID> findGroupIdsByUserId(@Param("userId") UUID userId);

  @Query("select m.userId from GroupMembership m where m.group.id = :groupId")
  Set<UUID> findUserIdsByGroupId(@Param("groupId") UUID groupId);
}
