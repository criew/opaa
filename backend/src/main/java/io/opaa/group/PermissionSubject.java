package io.opaa.group;

import io.opaa.api.types.PermissionSubjectType;
import java.util.Objects;
import java.util.UUID;

/**
 * A permission entry references a user or a group, never both. This is the shared abstraction that
 * a future grant (asset permissions, #202) will point at; this issue introduces the abstraction and
 * its group-membership resolution ({@link GroupMembershipResolver}) ahead of the first grant,
 * because retrofitting a subject type later would touch every grant, query and cache.
 *
 * <p>Carries {@code organizationId} so that {@link GroupMembershipResolver#resolveUserIds} can
 * enforce the organization boundary itself, at the one place every future caller goes through,
 * instead of leaving each caller in #202 to rebuild that check independently - the same class of
 * bug the boundary work in #199 exists to prevent.
 */
public record PermissionSubject(PermissionSubjectType type, UUID id, UUID organizationId) {

  public PermissionSubject {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(organizationId, "organizationId must not be null");
  }

  public static PermissionSubject user(UUID userId, UUID organizationId) {
    return new PermissionSubject(PermissionSubjectType.USER, userId, organizationId);
  }

  public static PermissionSubject group(UUID groupId, UUID organizationId) {
    return new PermissionSubject(PermissionSubjectType.GROUP, groupId, organizationId);
  }
}
