package io.opaa.library;

import io.opaa.api.dto.AssetGrantRequest;
import io.opaa.api.dto.AssetGrantResponse;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.Group;
import io.opaa.group.GroupRepository;
import io.opaa.group.PermissionSubjectType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manages {@link AssetGrant}s on a {@link KnowledgeLibrary} - the "who has which {@link AssetRole}"
 * side of #202. Every mutating method requires {@link AssetRole#MANAGER} on the target library,
 * resolved through {@link LibraryAccessService}, which is also where the cache this class
 * invalidates after every write lives.
 *
 * <p><b>Escalation guards (#202 code review), both directions:</b> {@code MANAGER} being able to
 * touch grants at all does not mean it may act on a role higher than its own, in either direction -
 * {@link #requireCallerRoleAtLeast} enforces {@code callerRole >= otherRole} everywhere a role is
 * compared, and every mutating path calls it twice:
 *
 * <ul>
 *   <li><b>The role being granted or requested</b> ({@link #upsertGrant}) - a {@code MANAGER} could
 *       otherwise grant itself {@code OWNER} and then delete the library or transfer ownership,
 *       rights the specification reserves for {@code OWNER} alone.
 *   <li><b>The role already held by the grant being changed or removed</b> ({@link #upsertGrant}'s
 *       update path and {@link #revokeGrant}) - without this half, a {@code MANAGER} could
 *       downgrade or revoke an {@code OWNER}'s own grant even though it could never have granted
 *       {@code OWNER} in the first place (#202 code review round 2, "Rollen-Deckelung wirkt nur in
 *       eine Richtung"): the class Javadoc's earlier claim that these two checks were already a
 *       "mirror image" was wrong until this second half was added - only capping the requested role
 *       left the role of an <em>existing</em> grant uncompared to the caller's own.
 * </ul>
 *
 * <p>Independently of the role-escalation guards above, {@link #revokeGrant} and {@link
 * #upsertGrant}'s update path also refuse to leave a library with zero active {@code OWNER} grants
 * - removing or downgrading the last one would leave nobody able to manage the library at all, not
 * even to grant a new {@code OWNER}. This count is taken <em>after</em> the intended change,
 * including any new {@code expiresAt} the caller is setting (#202 code review round 2, nit 1): an
 * {@code OWNER} renewing their own grant with {@code role = OWNER} does not by itself prove the
 * grant stays active if the caller also supplies an {@code expiresAt} in the past. See {@link
 * #requireNotDowngradingTheLastActiveOwnerGrant}, {@link
 * AssetGrantRepository#lockLibraryGrantsForMutation} and {@link
 * AssetGrantRepository#countOtherActiveOwnerGrants} for how this count is additionally protected
 * against two concurrent callers each observing the other's OWNER grant as still active (#202 code
 * review round 2 nit 2), first via a locked entity read that turned out to be stale under that
 * exact scenario, then via a {@code SELECT ... FOR UPDATE} on the grant rows that fixed the
 * staleness but deadlocked under real concurrency, and settling on a per-library advisory lock plus
 * a plain scalar count (round 3, blocker 2) - see both methods' Javadoc for the full history.
 *
 * <p>Also enforces two narrower rules from the feature spec: a grant can never be created or
 * updated on the automatic personal library (it is meant to reach only its owner, see {@code
 * KnowledgeLibraryService#updateLibrary}'s identical guard against widening its visibility), and a
 * grant can never be created or updated to target a dissolved group ({@code
 * docs/features/spaces-and-assets.md#reorganisation-umbenennung-zusammenlegung}: "bestehende Grants
 * bleiben bestehen ... koennen aber nicht erweitert werden" - existing grants to a group that was
 * dissolved keep working, but no new or updated grant may target it).
 */
@Service
@Transactional(readOnly = true)
public class AssetGrantService {

  private final AssetGrantRepository grantRepository;
  private final KnowledgeLibraryRepository libraryRepository;
  private final UserRepository userRepository;
  private final GroupRepository groupRepository;
  private final LibraryAccessService accessService;

  public AssetGrantService(
      AssetGrantRepository grantRepository,
      KnowledgeLibraryRepository libraryRepository,
      UserRepository userRepository,
      GroupRepository groupRepository,
      LibraryAccessService accessService) {
    this.grantRepository = grantRepository;
    this.libraryRepository = libraryRepository;
    this.userRepository = userRepository;
    this.groupRepository = groupRepository;
    this.accessService = accessService;
  }

  public List<AssetGrantResponse> listGrants(
      UUID libraryId, UUID currentUserId, boolean systemAdmin) {
    KnowledgeLibrary library = requireManageable(libraryId, currentUserId, systemAdmin);
    return grantRepository.findByLibraryId(library.getId()).stream()
        .map(AssetGrantService::toResponse)
        .toList();
  }

  @Transactional
  public AssetGrantResponse upsertGrant(
      UUID libraryId, AssetGrantRequest request, UUID currentUserId, boolean systemAdmin) {
    KnowledgeLibrary library = requireManageable(libraryId, currentUserId, systemAdmin);
    User currentUser = requireUser(currentUserId);

    if (request.getSubjectType() == null || request.getSubjectId() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "subjectType und subjectId sind erforderlich");
    }
    if (request.getRole() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role ist erforderlich");
    }
    // The personal library is meant to reach only its owner (KnowledgeLibraryService#createLibrary
    // grants that OWNER role directly) - a grant through this API would reopen exactly the leak
    // KnowledgeLibraryService#updateLibrary already closes for widening its visibility.
    if (library.isPersonal()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Auf die persoenliche Bibliothek koennen keine Berechtigungen vergeben werden");
    }
    // Escalation guard, half 1: a caller may never grant a role higher than the one they
    // themselves hold - see the class Javadoc. requireManageable already established callerRole is
    // at least MANAGER.
    AssetRole callerRole = accessService.effectiveRole(library, currentUserId, systemAdmin);
    requireCallerRoleAtLeast(
        callerRole,
        request.getRole(),
        "Die eigene Rolle reicht nicht aus, um die Rolle " + request.getRole() + " zu vergeben");

    AssetGrant grant;
    if (request.getSubjectType() == PermissionSubjectType.USER) {
      requireUserInOrganization(request.getSubjectId(), library.getOrganizationId());
      grant =
          grantRepository
              .findByLibraryIdAndSubjectTypeAndSubjectUserId(
                  library.getId(), PermissionSubjectType.USER, request.getSubjectId())
              .orElse(null);
      if (grant == null) {
        grant =
            AssetGrant.forUser(
                library.getId(),
                library.getOrganizationId(),
                request.getSubjectId(),
                request.getRole(),
                request.getExpiresAt(),
                currentUser.getId());
      } else {
        requireCallerCanTouchExistingGrant(callerRole, grant, "aendern");
        requireNotDowngradingTheLastActiveOwnerGrant(
            library.getId(), grant, request.getRole(), request.getExpiresAt());
        grant.updateRole(request.getRole(), request.getExpiresAt());
      }
    } else {
      requireGrantableGroup(request.getSubjectId(), library.getOrganizationId());
      grant =
          grantRepository
              .findByLibraryIdAndSubjectTypeAndSubjectGroupId(
                  library.getId(), PermissionSubjectType.GROUP, request.getSubjectId())
              .orElse(null);
      if (grant == null) {
        grant =
            AssetGrant.forGroup(
                library.getId(),
                library.getOrganizationId(),
                request.getSubjectId(),
                request.getRole(),
                request.getExpiresAt(),
                currentUser.getId());
      } else {
        requireCallerCanTouchExistingGrant(callerRole, grant, "aendern");
        requireNotDowngradingTheLastActiveOwnerGrant(
            library.getId(), grant, request.getRole(), request.getExpiresAt());
        grant.updateRole(request.getRole(), request.getExpiresAt());
      }
    }

    AssetGrant saved = grantRepository.save(grant);
    invalidateAfterCommit(library.getId());
    return toResponse(saved);
  }

  @Transactional
  public void revokeGrant(UUID libraryId, UUID grantId, UUID currentUserId, boolean systemAdmin) {
    KnowledgeLibrary library = requireManageable(libraryId, currentUserId, systemAdmin);
    AssetGrant grant =
        grantRepository
            .findById(grantId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Berechtigung nicht gefunden"));
    if (!grant.getLibraryId().equals(library.getId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Berechtigung nicht gefunden");
    }
    // Escalation guard, half 2 - see the class Javadoc: a caller may never touch a grant that
    // already carries a role higher than their own, regardless of whether they could have
    // *granted* that role in the first place.
    AssetRole callerRole = accessService.effectiveRole(library, currentUserId, systemAdmin);
    requireCallerCanTouchExistingGrant(callerRole, grant, "entfernen");

    // Last-active-OWNER guard, the mirror image of upsertGrant's downgrade guard: removing the
    // last non-expired OWNER grant would leave the library in a state the application can no
    // longer manage - nobody left with the role required to grant, revoke or delete. See the class
    // Javadoc and AssetGrantRepository#lockLibraryGrantsForMutation for why this locks per library
    // via an advisory lock before counting, rather than row-locking the grants directly.
    if (grant.getRole() == AssetRole.OWNER
        && !grant.isExpired(Instant.now())
        && isLastActiveOwnerGrant(library.getId(), grant.getId())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Die letzte OWNER-Berechtigung einer Bibliothek kann nicht entfernt werden");
    }

    grantRepository.delete(grant);
    invalidateAfterCommit(library.getId());
  }

  /**
   * Escalation guard, half 2 (see the class Javadoc): whether {@code callerRole} is at least as
   * privileged as the role an existing grant already carries, before that grant may be changed or
   * removed. {@code action} is the German verb ("aendern"/"entfernen") for the resulting message.
   */
  private void requireCallerCanTouchExistingGrant(
      AssetRole callerRole, AssetGrant existingGrant, String action) {
    requireCallerRoleAtLeast(
        callerRole,
        existingGrant.getRole(),
        "Die eigene Rolle reicht nicht aus, um eine bestehende "
            + existingGrant.getRole()
            + "-Berechtigung zu "
            + action);
  }

  /**
   * Throws {@code 403} unless {@code callerRole} is at least as privileged as {@code otherRole}.
   */
  private void requireCallerRoleAtLeast(AssetRole callerRole, AssetRole otherRole, String message) {
    if (callerRole == null || otherRole.ordinal() > callerRole.ordinal()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
  }

  /**
   * Whether {@code excludingGrantId} is the library's only active {@code OWNER} grant, i.e.
   * removing or downgrading it would leave zero. Acquires {@link
   * AssetGrantRepository#lockLibraryGrantsForMutation}'s per-library advisory lock first, then
   * counts via {@link AssetGrantRepository#countOtherActiveOwnerGrants} - see both methods' Javadoc
   * for why this two-step, lock-then-plain-read sequence is both deadlock- and staleness-safe where
   * a single {@code SELECT ... FOR UPDATE} on the grant rows was neither.
   */
  private boolean isLastActiveOwnerGrant(UUID libraryId, UUID excludingGrantId) {
    grantRepository.lockLibraryGrantsForMutation(libraryId);
    return grantRepository.countOtherActiveOwnerGrants(libraryId, excludingGrantId, Instant.now())
        == 0;
  }

  /**
   * The same guard as {@link #revokeGrant}'s, applied to {@link #upsertGrant}'s update path:
   * lowering an existing OWNER grant's role is exactly as dangerous as revoking it outright if it
   * is the last active one - both leave nobody able to manage the library at all.
   *
   * <p>The count is taken <em>after</em> the intended change (#202 code review round 2, nit 1):
   * {@code newRole == OWNER} alone does not prove the grant stays an active owner - the caller may
   * also be setting {@code newExpiresAt} to a point in the past, which expires it immediately. Only
   * {@code newRole == OWNER} combined with a {@code newExpiresAt} that is either absent or still in
   * the future counts as "stays active" and skips the check below.
   */
  private void requireNotDowngradingTheLastActiveOwnerGrant(
      UUID libraryId, AssetGrant existingGrant, AssetRole newRole, Instant newExpiresAt) {
    Instant now = Instant.now();
    if (existingGrant.getRole() != AssetRole.OWNER || existingGrant.isExpired(now)) {
      return;
    }
    boolean staysActiveOwner =
        newRole == AssetRole.OWNER && (newExpiresAt == null || newExpiresAt.isAfter(now));
    if (staysActiveOwner) {
      return;
    }
    // See AssetGrantRepository#lockLibraryGrantsForMutation for why this locks per library via an
    // advisory lock before counting, rather than row-locking the grants directly (#202 code review
    // round 2 nit 2, round 3 blocker 2).
    if (isLastActiveOwnerGrant(libraryId, existingGrant.getId())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Die letzte OWNER-Berechtigung einer Bibliothek kann nicht herabgestuft werden");
    }
  }

  private KnowledgeLibrary requireManageable(
      UUID libraryId, UUID currentUserId, boolean systemAdmin) {
    User currentUser = requireUser(currentUserId);
    KnowledgeLibrary library =
        libraryRepository
            .findById(libraryId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Bibliothek nicht gefunden"));
    if (!library.getOrganizationId().equals(currentUser.getOrganizationId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Bibliothek nicht gefunden");
    }
    if (!accessService.canManage(library, currentUserId, systemAdmin)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Bibliothek");
    }
    return library;
  }

  private User requireUser(UUID userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Benutzer nicht gefunden"));
  }

  private void requireUserInOrganization(UUID userId, UUID organizationId) {
    User user = requireUser(userId);
    if (!user.getOrganizationId().equals(organizationId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Benutzer nicht gefunden");
    }
  }

  /**
   * Resolves a group, enforces the organization boundary, and rejects a dissolved group as a grant
   * target - see the class Javadoc for why: existing grants to a dissolved group keep working (see
   * {@link LibraryAccessService#effectiveRole}, which does not check {@link Group#isDissolved()}
   * either), but no new or updated grant may target it.
   */
  private void requireGrantableGroup(UUID groupId, UUID organizationId) {
    Group group =
        groupRepository
            .findById(groupId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Gruppe nicht gefunden"));
    if (!group.getOrganizationId().equals(organizationId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Gruppe nicht gefunden");
    }
    if (group.isDissolved()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Die Gruppe ist aufgeloest und kann keine neuen Berechtigungen mehr erhalten");
    }
  }

  /**
   * Defers cache invalidation until the enclosing transaction has finished - the same reasoning and
   * the same {@code afterCompletion} (not {@code afterCommit}) choice as {@code
   * GroupService#invalidateAfterCommit}, so a rollback also evicts the entry this transaction may
   * have touched. Falls back to running immediately when no transaction is active.
   */
  private void invalidateAfterCommit(UUID libraryId) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      accessService.invalidateLibrary(libraryId);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            accessService.invalidateLibrary(libraryId);
          }
        });
  }

  private static AssetGrantResponse toResponse(AssetGrant grant) {
    return new AssetGrantResponse(
            grant.getId(),
            grant.getSubjectType(),
            grant.getSubjectId(),
            grant.getRole(),
            grant.getCreatedAt(),
            grant.getUpdatedAt())
        .expiresAt(grant.getExpiresAt())
        .grantedByUserId(grant.getGrantedByUserId());
  }
}
