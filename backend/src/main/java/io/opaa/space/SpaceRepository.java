package io.opaa.space;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpaceRepository extends JpaRepository<Space, UUID> {

  List<Space> findDistinctByMembershipsUserId(UUID userId);

  @Query(
      "select distinct s from Space s "
          + "left join fetch s.memberships "
          + "where s.id in (select m.space.id from SpaceMembership m where m.userId = :userId)")
  List<Space> findDistinctByMembershipsUserIdWithMemberships(@Param("userId") UUID userId);

  @Query("select distinct s from Space s left join fetch s.memberships where s.id = :spaceId")
  Optional<Space> findByIdWithMemberships(@Param("spaceId") UUID spaceId);

  boolean existsByOwnerIdAndKind(UUID ownerId, SpaceKind kind);
}
