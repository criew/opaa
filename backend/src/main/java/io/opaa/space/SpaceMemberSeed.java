package io.opaa.space;

import io.opaa.api.types.PermissionSubjectType;
import io.opaa.api.types.SpaceRole;
import java.util.UUID;

/**
 * One person or group to admit when a space is created - the domain counterpart of the generated
 * request. A {@code null} role means {@code MEMBER}, as when a member is added later.
 */
public record SpaceMemberSeed(PermissionSubjectType subjectType, UUID subjectId, SpaceRole role) {

  public static SpaceMemberSeed user(UUID userId, SpaceRole role) {
    return new SpaceMemberSeed(PermissionSubjectType.USER, userId, role);
  }

  public static SpaceMemberSeed group(UUID groupId, SpaceRole role) {
    return new SpaceMemberSeed(PermissionSubjectType.GROUP, groupId, role);
  }
}
