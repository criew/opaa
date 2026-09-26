package io.opaa.auth;

import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditPersonDirectory;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Answers {@link AuditPersonDirectory} from {@link UserRepository}. */
@Component
class AuditPersonDirectoryAdapter implements AuditPersonDirectory {

  private final UserRepository users;

  AuditPersonDirectoryAdapter(UserRepository users) {
    this.users = users;
  }

  @Override
  public Optional<SystemRole> systemRoleOf(UUID organizationId, UUID userId) {
    return users.findByIdAndOrganizationId(userId, organizationId).map(User::getSystemRole);
  }

  @Override
  public boolean belongsTo(UUID organizationId, UUID userId) {
    return users.findByIdAndOrganizationId(userId, organizationId).isPresent();
  }
}
