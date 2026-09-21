package io.opaa.space;

import io.opaa.api.types.SpaceRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.GroupSubject;
import io.opaa.permission.GroupSubjectDirectory;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Centralizes the space-membership authorization decisions previously re-implemented, with subtly
 * different owner semantics, in {@code SpaceService} and {@code SpaceAssetAssociationService}
 * (#888).
 *
 * <p><b>A membership names a subject, not a person</b> (#1815, ADR-0036 Entscheidung 6). {@link
 * #effectiveRole} is therefore the best of three sources: the caller's own membership row, the
 * memberships of the groups they belong to, and ownership. A right held through a group ends the
 * moment the membership does - the group resolution runs through {@link GroupMembershipResolver},
 * whose cache is invalidated after the writing transaction commits, so there is no rights cache
 * here that could outlive the withdrawal. This is also why the class is a bean rather than the
 * static utility it was before #1815: a caller that forgot to pass the group memberships in would
 * silently under-grant, and every call site would have had to remember.
 *
 * <p><b>Owner ⇒ {@link SpaceRole#ADMIN}:</b> {@link #effectiveRole} always treats the space owner
 * as at least {@link SpaceRole#ADMIN}, regardless of their actual {@link SpaceMembership} role
 * (#888) - {@code SpaceService#transferOwnership} never raises the new owner's own membership role,
 * making "owner with a MEMBER or CURATOR membership" a legal and persistent state.
 *
 * <p>System-admin bypass is deliberately <em>not</em> folded into {@link #effectiveRole}: it is an
 * organization-wide privilege, orthogonal to a space's own membership, and each guard below only
 * applies it where the pre-#888 call site already did - see each method's Javadoc.
 */
@Component
public class SpaceAccessPolicy {

  private final GroupMembershipResolver groupMemberships;
  private final GroupSubjectDirectory groupDirectory;
  private final SpaceMembershipRepository membershipRepository;

  SpaceAccessPolicy(
      GroupMembershipResolver groupMemberships,
      GroupSubjectDirectory groupDirectory,
      SpaceMembershipRepository membershipRepository) {
    this.groupMemberships = groupMemberships;
    this.groupDirectory = groupDirectory;
    this.membershipRepository = membershipRepository;
  }

  /**
   * The caller's effective role in {@code space}: the best of their own membership row, the
   * memberships of their groups, and {@link SpaceRole#ADMIN} if they own the space. {@code null} if
   * none of the three applies.
   *
   * <p><b>Not to be confused with {@code LibraryAccessService#effectiveRole}</b>, which folds a
   * system-admin bypass <em>into</em> its own return value. This method deliberately does the
   * opposite - see the class Javadoc - so a system admin who is not a member of {@code space} still
   * gets {@code null} here.
   */
  public SpaceRole effectiveRole(Space space, UUID userId) {
    return effectiveRole(space, userId, groupMemberships.groupIdsForUser(userId));
  }

  /** Convenience overload of {@link #effectiveRole(Space, UUID)} taking a {@link CurrentUser}. */
  public SpaceRole effectiveRole(Space space, CurrentUser caller) {
    return effectiveRole(space, caller.id());
  }

  /**
   * The role computation itself, with the caller's group memberships already resolved - the form a
   * caller uses that evaluates many spaces for the same person and must not resolve the groups once
   * per space ({@code SpaceService#listSpaces}).
   */
  public static SpaceRole effectiveRole(Space space, UUID userId, Set<UUID> groupIds) {
    SpaceRole best = space.getOwnerId().equals(userId) ? SpaceRole.ADMIN : null;
    for (SpaceMembership membership : space.getMemberships()) {
      boolean applies =
          membership.isUserSubject()
              ? userId.equals(membership.getUserId())
              : groupIds.contains(membership.getGroupId());
      if (applies) {
        best = best == null || membership.getRole().atLeast(best) ? membership.getRole() : best;
      }
    }
    return best;
  }

  /** Whether the caller's {@link #effectiveRole} ranks at or above {@code minRole}. */
  public boolean hasAtLeast(Space space, UUID userId, SpaceRole minRole) {
    SpaceRole role = effectiveRole(space, userId);
    return role != null && role.atLeast(minRole);
  }

  /** Whether the user reaches {@code spaceId} at all - directly or through one of their groups. */
  public boolean isMember(UUID spaceId, UUID userId) {
    if (membershipRepository.existsBySpaceIdAndUserId(spaceId, userId)) {
      return true;
    }
    Set<UUID> groupIds = groupMemberships.groupIdsForUser(userId);
    return !groupIds.isEmpty()
        && membershipRepository.existsBySpaceIdAndGroupIdIn(spaceId, groupIds);
  }

  /**
   * Requires that the caller reaches {@code space}, bypassed for a system administrator - mirrors
   * {@code SpaceService#getSpace}'s pre-#888 inline check.
   */
  public void requireMember(Space space, CurrentUser caller) {
    if (caller.isSystemAdmin()) {
      return;
    }
    if (effectiveRole(space, caller) == null) {
      throw new AccessDeniedException("Sie sind kein Mitglied dieses Space");
    }
  }

  /**
   * Requires ADMIN {@link #effectiveRole} - i.e. an ADMIN member, a member of an ADMIN group, or
   * the owner. No system-admin bypass, bewusst wie vor #888: {@code SpaceService}'s pre-#888 {@code
   * requireManager} had none either, and its callers ({@code addMember}, {@code updateMemberRole},
   * {@code removeMember}) do not check {@code caller.isSystemAdmin()} themselves either. A
   * pre-existing gap, not something #888 or #1815 introduces.
   *
   * <p>Before #1815 this additionally required an actual {@link SpaceMembership} row to exist, even
   * for the owner. That requirement is gone, and had to go: a person who is ADMIN through a group
   * has no row of their own, which is the entire point of a group membership.
   */
  public void requireManager(Space space, CurrentUser caller) {
    requireAtLeast(space, caller, "Nur Administratoren können Mitglieder verwalten");
  }

  /**
   * #144: the member list is restricted to ADMIN, the owner and system admins - and therefore a
   * group is named only to those who manage its membership here (ADR-0036, Entscheidung 9). {@code
   * MEMBER} and {@code CURATOR} only ever see the aggregated {@code roleCounts}.
   */
  public void requireMemberListViewer(Space space, CurrentUser caller) {
    requireAtLeast(
        space,
        caller,
        "Nur Administratoren oder der Eigentümer können die Mitgliederliste einsehen");
  }

  /**
   * Requires CURATOR {@link #effectiveRole} or above, bypassed for a system administrator - mirrors
   * {@code SpaceAssetAssociationService}'s pre-#888 {@code requireCurator}, whose {@code
   * hasCuratorRole} already treated the owner as a curator.
   */
  public void requireCurator(Space space, CurrentUser caller) {
    if (caller.isSystemAdmin()) {
      return;
    }
    if (!hasAtLeast(space, caller.id(), SpaceRole.CURATOR)) {
      throw new AccessDeniedException("Nur Kuratoren dieses Space können Bibliotheken zuordnen");
    }
  }

  /**
   * Whether the space still has somebody who can act for it: the owner, an {@code ADMIN} person, or
   * an {@code ADMIN} group that is capable of acting. Its negation is the derived state "Nachfolge
   * offen" (ADR-0036, Entscheidung 6) - derived on every read, never stored, so no write path can
   * forget to set or clear a flag.
   *
   * <p><b>Two limits, stated rather than hidden.</b> First, the person half is unconditional: an
   * account state that would make a person incapable arrives with #1818, so until then every
   * account counts. Second, the owner is reached through their own membership row, and every space
   * created or transferred through the API has one - so "Nachfolge offen" is <em>not reachable</em>
   * for a space today. What is decided here today is the group half: whether a group still counts
   * as this space's {@code ADMIN}.
   *
   * <p><b>What the state does not do yet:</b> ADR-0036, Entscheidung 6 freezes an object's reach
   * while its succession is open - no new grants, no higher release level, for a space no new
   * members. Nothing here enforces that; the state is derived and shown, and the enforcement
   * belongs to the lifecycle work (#1819) together with the run that records the Vorgänge.
   */
  public boolean hasCapableAdmin(Space space) {
    return hasCapableAdminAfter(space, null, null);
  }

  /**
   * The same question asked about the state a pending change would leave behind: {@code changed}
   * removed ({@code newRole == null}) or set to {@code newRole}. This is what turns the protection
   * of ADR-0036, Entscheidung 6, Schutzregel 1 into a decision instead of an after-the-fact
   * observation - the space is never allowed to lose its last capable {@code ADMIN} through a
   * management action.
   */
  public boolean hasCapableAdminAfter(Space space, SpaceMembership changed, SpaceRole newRole) {
    // Person rows first, and deliberately in two passes: deciding a person costs nothing, deciding
    // a group costs a directory lookup and a count. On the list path of every space
    // (SpaceService#listSpaces) the first pass answers almost every case.
    for (SpaceMembership membership : space.getMemberships()) {
      if (membership.isUserSubject() && qualifies(space, membership, changed, newRole)) {
        return true;
      }
    }
    for (SpaceMembership membership : space.getMemberships()) {
      if (membership.isGroupSubject()
          && qualifies(space, membership, changed, newRole)
          && isCapableGroup(membership.getGroupId())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether the row would still rank as {@code ADMIN} after the pending change - the owner's own
   * row always does, whatever role it carries, unless the change removes it.
   */
  private static boolean qualifies(
      Space space, SpaceMembership membership, SpaceMembership changed, SpaceRole newRole) {
    SpaceRole role = membership == changed ? newRole : membership.getRole();
    if (role == null) {
      return false;
    }
    boolean ownerRow =
        membership.isUserSubject() && space.getOwnerId().equals(membership.getUserId());
    return ownerRow || role.atLeast(SpaceRole.ADMIN);
  }

  /**
   * Whether a group can act: effective (neither dissolved, nor belonging to a switched-off identity
   * provider, nor a token group whose provider has since switched to the directory run - ADR-0036
   * Entscheidung 6, extended by #1816) <em>and</em> reaching at least one active account. A group
   * that has lost its last account is still a member and keeps conferring its role on nobody - it
   * simply no longer counts as the space's {@code ADMIN}.
   */
  public boolean isCapableGroup(UUID groupId) {
    GroupSubject group = groupDirectory.find(groupId).orElse(null);
    if (group == null || group.dissolved() || group.providerDisabled() || group.unmaintained()) {
      return false;
    }
    return groupMemberships.activeMemberCount(groupId, group.organizationId()) > 0;
  }

  private void requireAtLeast(Space space, CurrentUser caller, String message) {
    SpaceRole role = effectiveRole(space, caller);
    if (role == null) {
      throw new AccessDeniedException("Sie sind kein Mitglied dieses Space");
    }
    if (!role.atLeast(SpaceRole.ADMIN)) {
      throw new AccessDeniedException(message);
    }
  }
}
