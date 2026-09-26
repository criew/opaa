package io.opaa.group;

import io.opaa.auth.local.LocalAccountGroupDirectory;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Answers {@link LocalAccountGroupDirectory} from this package's repository. */
@Component
class LocalAccountGroupDirectoryAdapter implements LocalAccountGroupDirectory {

  private final GroupMembershipRepository memberships;

  LocalAccountGroupDirectoryAdapter(GroupMembershipRepository memberships) {
    this.memberships = memberships;
  }

  @Override
  public long countGroupMemberships(UUID userId) {
    return memberships.countByUserId(userId);
  }
}
