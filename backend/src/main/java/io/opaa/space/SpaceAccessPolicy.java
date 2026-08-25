package io.opaa.space;

import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import java.util.UUID;

/**
 * Centralizes the space-membership authorization decisions previously re-implemented, with subtly
 * different owner semantics, in {@code SpaceService} and {@code SpaceAssetAssociationService}
 * (#888).
 *
 * <p><b>Owner ⇒ {@link SpaceRole#ADMIN}:</b> {@link #effectiveRole} always treats the space owner
 * as at least {@link SpaceRole#ADMIN}, regardless of their actual {@link SpaceMembership} role.
 * This is a deliberate unification, not a pre-existing rule: before #888, {@code
 * SpaceAssetAssociationService.hasCuratorRole} already treated the owner as a curator, while {@code
 * SpaceService.requireManager} checked only the raw membership role and did not treat the owner as
 * a manager - an inconsistency with no functional justification, since {@code
 * SpaceService#transferOwnership} never raises the new owner's own membership role, making "owner
 * with a MEMBER or CURATOR membership" a legal and persistent state. The concrete behavior change
 * this brings to {@code requireManager}'s callers ({@code addMember}, {@code updateMemberRole},
 * {@code removeMember}): an owner whose own membership role is below ADMIN may now perform those
 * manager actions too, where before they would have received a 403.
 *
 * <p>System-admin bypass is deliberately <em>not</em> folded into {@link #effectiveRole}: it is an
 * organization-wide privilege, orthogonal to a space's own membership, and each guard below only
 * applies it where the pre-#888 call site already did - see each method's Javadoc.
 */
public final class SpaceAccessPolicy {

  private SpaceAccessPolicy() {}

  /**
   * The caller's effective role in {@code space}: their own membership role, or {@link
   * SpaceRole#ADMIN} if they are the space's owner, whichever ranks higher. {@code null} if the
   * caller is neither a member nor the owner.
   *
   * <p><b>Not to be confused with {@code LibraryAccessService#effectiveRole}</b>, which folds a
   * system-admin bypass <em>into</em> its own return value (system admin ⇒ {@code AssetRole.OWNER}
   * unconditionally). This method deliberately does the opposite - system-admin bypass is never
   * folded in here, see the class Javadoc - so a system admin who is not a member of {@code space}
   * still gets {@code null} from this method, not {@link SpaceRole#ADMIN}.
   */
  public static SpaceRole effectiveRole(Space space, UUID userId) {
    SpaceMembership membership = membership(space, userId);
    boolean owner = space.getOwnerId().equals(userId);
    if (membership == null) {
      return owner ? SpaceRole.ADMIN : null;
    }
    return owner && !membership.getRole().atLeast(SpaceRole.ADMIN)
        ? SpaceRole.ADMIN
        : membership.getRole();
  }

  /** Convenience overload of {@link #effectiveRole(Space, UUID)} taking a {@link CurrentUser}. */
  public static SpaceRole effectiveRole(Space space, CurrentUser caller) {
    return effectiveRole(space, caller.id());
  }

  /** Whether the caller's {@link #effectiveRole} ranks at or above {@code minRole}. */
  public static boolean hasAtLeast(Space space, UUID userId, SpaceRole minRole) {
    SpaceRole role = effectiveRole(space, userId);
    return role != null && role.atLeast(minRole);
  }

  /**
   * Requires that the caller is a member of {@code space}, bypassed for a system administrator -
   * mirrors {@code SpaceService#getSpace}'s pre-#888 inline check.
   */
  public static void requireMember(Space space, CurrentUser caller) {
    if (caller.isSystemAdmin()) {
      return;
    }
    if (membership(space, caller.id()) == null) {
      throw new AccessDeniedException("Sie sind kein Mitglied dieses Space");
    }
  }

  /**
   * Requires ADMIN {@link #effectiveRole} - i.e. an ADMIN member or the owner. No system-admin
   * bypass, bewusst wie vor #888: {@code SpaceService}'s pre-#888 {@code requireManager} had none
   * either, and its callers ({@code addMember}, {@code updateMemberRole}, {@code removeMember}) do
   * not check {@code caller.isSystemAdmin()} themselves either - a system admin who is not already
   * an ADMIN member (or the owner) of this particular space cannot manage its members. A
   * pre-existing gap, not something #888 introduces or was asked to close.
   *
   * <p>Requires an actual {@link SpaceMembership} row to exist even for the owner: the "kein
   * Mitglied"/"no member" branch below checks raw membership presence, not {@link #effectiveRole},
   * so an owner without any membership row (unreachable through the API - {@code transferOwnership}
   * only ever transfers to an existing member, and every space is created with its owner as an
   * ADMIN member) would still be rejected here rather than silently treated as ADMIN.
   */
  public static SpaceMembership requireManager(Space space, CurrentUser caller) {
    SpaceMembership membership = membership(space, caller.id());
    if (membership == null) {
      throw new AccessDeniedException("Sie sind kein Mitglied dieses Space");
    }
    if (!effectiveRole(space, caller).atLeast(SpaceRole.ADMIN)) {
      throw new AccessDeniedException("Nur Administratoren können Mitglieder verwalten");
    }
    return membership;
  }

  /**
   * #144: the member list is restricted to ADMIN, the owner and system admins - the owner check was
   * already explicit before #888 ({@code transferOwnership} never raises the new owner's own
   * membership role), so routing it through {@link #effectiveRole} here changes no behavior.
   *
   * <p>Like {@link #requireManager}, this requires an actual membership row even for the owner -
   * see that method's Javadoc for why that state is unreachable through the API in practice.
   */
  public static SpaceMembership requireMemberListViewer(Space space, CurrentUser caller) {
    SpaceMembership membership = membership(space, caller.id());
    if (membership == null) {
      throw new AccessDeniedException("Sie sind kein Mitglied dieses Space");
    }
    if (!effectiveRole(space, caller).atLeast(SpaceRole.ADMIN)) {
      throw new AccessDeniedException(
          "Nur Administratoren oder der Eigentümer können die Mitgliederliste einsehen");
    }
    return membership;
  }

  /**
   * Requires CURATOR {@link #effectiveRole} or above, bypassed for a system administrator - mirrors
   * {@code SpaceAssetAssociationService}'s pre-#888 {@code requireCurator}, whose {@code
   * hasCuratorRole} already treated the owner as a curator.
   */
  public static void requireCurator(Space space, CurrentUser caller) {
    if (caller.isSystemAdmin()) {
      return;
    }
    if (!hasAtLeast(space, caller.id(), SpaceRole.CURATOR)) {
      throw new AccessDeniedException("Nur Kuratoren dieses Space können Bibliotheken zuordnen");
    }
  }

  private static SpaceMembership membership(Space space, UUID userId) {
    return space.getMemberships().stream()
        .filter(m -> m.getUserId().equals(userId))
        .findFirst()
        .orElse(null);
  }
}
