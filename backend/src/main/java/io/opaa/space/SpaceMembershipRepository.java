package io.opaa.space;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpaceMembershipRepository extends JpaRepository<SpaceMembership, UUID> {

  List<SpaceMembership> findBySpaceId(UUID spaceId);

  Optional<SpaceMembership> findByUserIdAndSpaceId(UUID userId, UUID spaceId);
}
