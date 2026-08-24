package io.opaa.auth;

import java.util.UUID;

/**
 * Snapshot of the calling user's identity for the current request. Populated once by {@link
 * UserProvisioningFilter} from the {@link User} row it already loads for every authenticated
 * request, and handed to controllers/services from there — no further {@code
 * findBySubjectAndIssuer} lookup for the caller's own identity.
 *
 * <p>Deliberately a value snapshot, not a reference to the {@link User} entity: it stays valid past
 * the request's persistence context without a lazy-load or detached-entity risk. Code that needs
 * the live entity (e.g. to mutate it) loads it explicitly through {@link UserService} and says so
 * at the call site — this type never substitutes for that.
 */
public record CurrentUser(UUID id, UUID organizationId, SystemRole systemRole, String displayName) {

  public boolean isSystemAdmin() {
    return systemRole == SystemRole.SYSTEM_ADMIN;
  }

  static CurrentUser from(User user) {
    return new CurrentUser(
        user.getId(), user.getOrganizationId(), user.getSystemRole(), user.getDisplayName());
  }
}
