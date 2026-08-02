package io.opaa.group;

import java.util.Objects;
import java.util.UUID;

/**
 * A permission entry references a user or a group, never both. This is the shared abstraction that
 * a future grant (asset permissions, #202) will point at; this issue introduces the abstraction and
 * its group-membership resolution ({@link GroupMembershipResolver}) ahead of the first grant,
 * because retrofitting a subject type later would touch every grant, query and cache.
 */
public record PermissionSubject(PermissionSubjectType type, UUID id) {

  public PermissionSubject {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(id, "id must not be null");
  }

  public static PermissionSubject user(UUID userId) {
    return new PermissionSubject(PermissionSubjectType.USER, userId);
  }

  public static PermissionSubject group(UUID groupId) {
    return new PermissionSubject(PermissionSubjectType.GROUP, groupId);
  }
}
