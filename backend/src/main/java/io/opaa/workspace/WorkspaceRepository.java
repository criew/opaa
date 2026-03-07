package io.opaa.workspace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

  List<Workspace> findDistinctByMembershipsUserId(UUID userId);

  @Query(
      "select distinct w from Workspace w "
          + "left join fetch w.memberships "
          + "where w.id in (select m.workspace.id from WorkspaceMembership m where m.userId = :userId)")
  List<Workspace> findDistinctByMembershipsUserIdWithMemberships(@Param("userId") UUID userId);

  @Query(
      "select distinct w from Workspace w left join fetch w.memberships where w.id = :workspaceId")
  Optional<Workspace> findByIdWithMemberships(@Param("workspaceId") UUID workspaceId);

  boolean existsByNameIgnoreCase(String name);
}
