package io.opaa.permission;

import java.util.Objects;
import java.util.UUID;

/**
 * One space a group is a member of (#1815, ADR-0036 Entscheidung 6) - the pair every caller of
 * {@link GroupSpaceMembershipDirectory} derives its own answer from: a group deletion only needs to
 * know whether the list is empty, a provider deletion counts memberships and distinct spaces for
 * the sentence its 409 carries.
 */
public record GroupSpaceMembershipRef(UUID groupId, UUID spaceId) {

  public GroupSpaceMembershipRef {
    Objects.requireNonNull(groupId, "groupId must not be null");
    Objects.requireNonNull(spaceId, "spaceId must not be null");
  }
}
