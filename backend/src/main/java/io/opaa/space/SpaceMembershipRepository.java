package io.opaa.space;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpaceMembershipRepository extends JpaRepository<SpaceMembership, UUID> {

  List<SpaceMembership> findBySpaceId(UUID spaceId);

  Optional<SpaceMembership> findByUserIdAndSpaceId(UUID userId, UUID spaceId);

  /** How many spaces the account is a member of - part of what a handover moves (#1563). */
  long countByUserId(UUID userId);
}
