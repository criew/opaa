package io.opaa.permission;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Where a group is a space member - the port that lets {@code io.opaa.group} and {@code
 * io.opaa.auth.oidc} refuse a deletion with a countable reason without either of them knowing
 * {@code io.opaa.space} (ADR-0036, Entscheidung 12). Implemented by {@code
 * io.opaa.space.GroupSpaceMembershipDirectoryAdapter}.
 *
 * <p>{@code space_memberships.group_id} is {@code ON DELETE RESTRICT} (changelog 043), so without
 * this port a group that is a space member would take its own deletion - and the deletion of its
 * identity provider - into a raw foreign-key violation instead of a 409 that names the work ahead.
 */
public interface GroupSpaceMembershipDirectory {

  /** Every (group, space) pair among {@code groupIds}; an empty list for an empty input. */
  List<GroupSpaceMembershipRef> spaceMembershipsOf(Collection<UUID> groupIds);
}
