package io.opaa.space;

import io.opaa.permission.GroupSpaceMembershipDirectory;
import io.opaa.permission.GroupSpaceMembershipRef;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Answers {@link GroupSpaceMembershipDirectory} from this package's repository - the one place the
 * group administration and the provider administration learn that a group is a space member,
 * without either of them depending on {@code io.opaa.space} (ADR-0036, Entscheidung 12).
 */
@Component
class GroupSpaceMembershipDirectoryAdapter implements GroupSpaceMembershipDirectory {

  private final SpaceMembershipRepository membershipRepository;

  GroupSpaceMembershipDirectoryAdapter(SpaceMembershipRepository membershipRepository) {
    this.membershipRepository = membershipRepository;
  }

  @Override
  public List<GroupSpaceMembershipRef> spaceMembershipsOf(Collection<UUID> groupIds) {
    if (groupIds.isEmpty()) {
      return List.of();
    }
    return membershipRepository.findSpaceMembershipsOfGroups(groupIds);
  }
}
