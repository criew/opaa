package io.opaa.space;

import io.opaa.auth.local.LocalAccountSpaceDirectory;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Answers {@link LocalAccountSpaceDirectory} from this package's repositories. */
@Component
class LocalAccountSpaceDirectoryAdapter implements LocalAccountSpaceDirectory {

  private final SpaceRepository spaces;
  private final SpaceMembershipRepository memberships;

  LocalAccountSpaceDirectoryAdapter(SpaceRepository spaces, SpaceMembershipRepository memberships) {
    this.spaces = spaces;
    this.memberships = memberships;
  }

  @Override
  public Optional<String> personalSpaceName(UUID userId) {
    return spaces.findByOwnerId(userId).stream()
        .filter(Space::isDefault)
        .map(Space::getName)
        .findFirst();
  }

  @Override
  public long countSpaceMemberships(UUID userId) {
    return memberships.countByUserId(userId);
  }
}
