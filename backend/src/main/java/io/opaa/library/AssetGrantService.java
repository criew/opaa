package io.opaa.library;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.group.Group;
import io.opaa.group.GroupRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 * <p>Also enforces a narrower rule from the feature spec: a grant can never be created or updated
 * to target a dissolved group ({@code
 * docs/features/spaces-and-assets.md#reorganisation-umbenennung-zusammenlegung}: "bestehende Grants
 * bleiben bestehen ... koennen aber nicht erweitert werden" - existing grants to a group that was
 * dissolved keep working, but no new or updated grant may target it).
 *
 * <p>Every response also carries {@code subjectDisplayName} and {@code grantedByDisplayName},
 * resolved here rather than left to the frontend (#423 code review) - see {@link #toViews(List)}.
 */
@Service
@Transactional(readOnly = true)
public class AssetGrantService {

  private final AssetGrantRepository grantRepository;
  private final KnowledgeLibraryRepository libraryRepository;
  private final UserRepository userRepository;
  private final GroupRepository groupRepository;
  private final LibraryAccessService accessService;
  private final AuditEventRecorder auditEventRecorder;
  private final ApplicationEventPublisher eventPublisher;

  public AssetGrantService(
      AssetGrantRepository grantRepository,
      KnowledgeLibraryRepository libraryRepository,
      UserRepository userRepository,
      GroupRepository groupRepository,
      LibraryAccessService accessService,
      AuditEventRecorder auditEventRecorder,
      ApplicationEventPublisher eventPublisher) {
    this.grantRepository = grantRepository;
    this.libraryRepository = libraryRepository;
    this.userRepository = userRepository;
    this.groupRepository = groupRepository;
    this.accessService = accessService;
    this.auditEventRecorder = auditEventRecorder;
    this.eventPublisher = eventPublisher;
  }

  public List<AssetGrantView> listGrants(UUID libraryId, CurrentUser caller) {
    KnowledgeLibrary library = requireManageable(libraryId, caller);
    return toViews(grantRepository.findByLibraryId(library.getId()));
  }

  // #392: noRollbackFor(AccessDeniedException) - without it, the DENIED audit entry the
  // escalation-guard catch block below writes would be undone by Spring's default rollback-on-any-
  // RuntimeException the moment the same exception is rethrown to the caller, defeating the entire
  // point of recording a rejected attempt (AuditLogService's Javadoc: "a rejected action ... is
  // recorded ... as part of its own successful flow, not implied by a leftover row from a
  // rolled-back attempt" - the DENIED write must itself be that successful flow, not a doomed one).
  // Safe here because every domain exception this method can throw - including the escalation
  // guard's - fires strictly before grantRepository.save(grant) below: nothing is ever persisted on
  // a path this annotation keeps from rolling back, so "not rolling back" changes nothing about the
  // (never attempted) grant write, only preserves the audit trail of the attempt.
  @Transactional(noRollbackFor = AccessDeniedException.class)
  public AssetGrantView upsertGrant(UUID libraryId, AssetGrantUpsert request, CurrentUser caller) {
    UUID currentUserId = caller.id();
    KnowledgeLibrary library = requireManageable(libraryId, caller);

    if (request.subjectType() == null || request.subjectId() == null) {
      throw new ValidationException("Empfänger ist erforderlich");
    }
    if (request.role() == null) {
      throw new ValidationException("Rolle ist erforderlich");
    }
    // #392 code review, finding 2: subject validation moved ahead of the escalation guard below.
    // The guard's catch block pseudonymises request.subjectId() as the DENIED entry's
    // subject_ref - audit_actor_pseudonyms.user_id carries
    // fk_audit_actor_pseudonyms_user_organization against users(id, organization_id) (migration
    // 017, composite as of migration 047), so pseudonymising an id that names no real user (a
    // bogus id, or a
    // valid id probed from outside this organization) violated that FK, turned a should-be-403 into
    // an unhandled 500, and - because a DataIntegrityViolationException is not an
    // AccessDeniedException, so upsertGrant's noRollbackFor did not apply - rolled back the very
    // transaction that would have recorded the attempt, losing the audit trail for exactly the
    // probing behaviour it exists to catch. Validating first turns an unknown or foreign subject
    // into the same 404 ("Benutzer/Gruppe nicht gefunden") every other unresolvable reference in
    // this class already produces, before either the escalation guard or its DENIED write ever run
    // - a request that cannot even name a real subject is not a recordable "rejected grant to
    // subject X", it is a plain 404, and the guard now only ever pseudonymises a subject that is
    // already known to exist in this organization.
    if (request.subjectType() == PermissionSubjectType.USER) {
      requireUserInOrganization(request.subjectId(), library.getOrganizationId());
    } else {
      requireGrantableGroup(request.subjectId(), library.getOrganizationId());
    }

    // Escalation guard, half 1: a caller may never grant a role higher than the one they
    // themselves hold - see the class Javadoc. requireManageable already established callerRole is
    // at least MANAGER.
    AssetRole callerRole =
        accessService.effectiveRole(library, currentUserId, caller.isSystemAdmin());
    try {
      requireCallerRoleAtLeast(
          callerRole,
          request.role(),
          "Die eigene Rolle reicht nicht aus, um die Rolle "
              + roleLabel(request.role())
              + " zu vergeben");
    } catch (AccessDeniedException denied) {
      // #392: the rejected attempt to grant a role higher than the caller's own is itself
      // protocol-worthy - "der zurueckgewiesene Versuch, sich eine hoehere Rolle zu geben, ist fuer
      // eine Pruefung oft der interessantere Vorgang" (docs/features/security-and-compliance.md).
      auditEventRecorder.recordUserActionOnSubject(
          AuditEvent.builder()
              .organizationId(library.getOrganizationId())
              .actor(currentUserId)
              .type(AuditEventType.ASSET_GRANT_GRANTED)
              .object(AuditObjectType.KNOWLEDGE_LIBRARY, library.getId(), library.getName())
              .subject(
                  request.subjectType() == PermissionSubjectType.USER
                      ? AuditSubjectKind.USER
                      : AuditSubjectKind.GROUP,
                  request.subjectId())
              .after(Map.of("role", request.role().name()))
              .outcome(AuditOutcome.DENIED)
              .reason(denied.getMessage())
              .build());
      throw denied;
    }

    // One reference instant for the whole upsert, so the revival check in AssetGrant#updateRole and
    // any expiry comparison below judge the same moment.
    Instant now = Instant.now();
    AssetGrant grant;
    boolean isNewGrant;
    // #392: captured before grant.updateRole() mutates the entity in place, further down - the
    // "before" half of an ASSET_GRANT_CHANGED entry.
    AssetRole previousRole = null;
    Instant previousExpiresAt = null;
    if (request.subjectType() == PermissionSubjectType.USER) {
      grant =
          grantRepository
              .findByLibraryIdAndSubjectTypeAndSubjectUserId(
                  library.getId(), PermissionSubjectType.USER, request.subjectId())
              .orElse(null);
      isNewGrant = grant == null;
      if (grant == null) {
        grant =
            AssetGrant.forUser(
                library.getId(),
                library.getOrganizationId(),
                request.subjectId(),
                request.role(),
                request.expiresAt(),
                currentUserId);
      } else {
        requireCallerCanTouchExistingGrant(callerRole, grant, "ändern");
        requireNotDowngradingTheLastActiveOwnerGrant(
            library.getId(), grant, request.role(), request.expiresAt());
        previousRole = grant.getRole();
        previousExpiresAt = grant.getExpiresAt();
        grant.updateRole(request.role(), request.expiresAt(), currentUserId, now);
      }
    } else {
      grant =
          grantRepository
              .findByLibraryIdAndSubjectTypeAndSubjectGroupId(
                  library.getId(), PermissionSubjectType.GROUP, request.subjectId())
              .orElse(null);
      isNewGrant = grant == null;
      if (grant == null) {
        grant =
            AssetGrant.forGroup(
                library.getId(),
                library.getOrganizationId(),
                request.subjectId(),
                request.role(),
                request.expiresAt(),
                currentUserId);
      } else {
        requireCallerCanTouchExistingGrant(callerRole, grant, "ändern");
        requireNotDowngradingTheLastActiveOwnerGrant(
            library.getId(), grant, request.role(), request.expiresAt());
        previousRole = grant.getRole();
        previousExpiresAt = grant.getExpiresAt();
        grant.updateRole(request.role(), request.expiresAt(), currentUserId, now);
      }
    }

    AssetGrant saved = grantRepository.save(grant);
    // #238: every grant change is historised as its own interval, with the operation that caused
    // it - GRANTED for a new grant, ROLE_CHANGED for an update to an existing one.
    if (isNewGrant) {
      // #892: one event, not a hand-paired PermissionHistoryService + AuditEventRecorder call -
      // GrantChanged's two listeners write the history interval and the audit entry, so forgetting
      // one of the two writes is structurally impossible.
      eventPublisher.publishEvent(
          new GrantChanged(
              library,
              saved,
              GrantChanged.Cause.GRANTED,
              currentUserId,
              null,
              grantAuditPayload(saved.getRole(), saved.getExpiresAt())));
    } else {
      eventPublisher.publishEvent(
          new GrantChanged(
              library,
              saved,
              GrantChanged.Cause.ROLE_CHANGED,
              currentUserId,
              grantAuditPayload(previousRole, previousExpiresAt),
              grantAuditPayload(saved.getRole(), saved.getExpiresAt())));
    }
    invalidateAfterCommit(library.getId());
    return toViews(List.of(saved)).get(0);
  }

  private Map<String, Object> grantAuditPayload(AssetRole role, Instant expiresAt) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("role", role.name());
    if (expiresAt != null) {
      payload.put("expiresAt", expiresAt.toString());
    }
    return payload;
  }

  @Transactional
  public void revokeGrant(UUID libraryId, UUID grantId, CurrentUser caller) {
    UUID currentUserId = caller.id();
    KnowledgeLibrary library = requireManageable(libraryId, caller);
    AssetGrant grant =
        grantRepository
            .findById(grantId)
            .orElseThrow(() -> new NotFoundException("Berechtigung nicht gefunden"));
    if (!grant.getLibraryId().equals(library.getId())) {
      throw new NotFoundException("Berechtigung nicht gefunden");
    }
    // Escalation guard, half 2 - see the class Javadoc: a caller may never touch a grant that
    // already carries a role higher than their own, regardless of whether they could have
    // *granted* that role in the first place.
    AssetRole callerRole =
        accessService.effectiveRole(library, currentUserId, caller.isSystemAdmin());
    requireCallerCanTouchExistingGrant(callerRole, grant, "entfernen");

    // Last-active-OWNER guard, the mirror image of upsertGrant's downgrade guard: removing the
    // last non-expired OWNER grant would leave the library in a state the application can no
    // longer manage - nobody left with the role required to grant, revoke or delete. See the class
    // Javadoc and AssetGrantRepository#lockLibraryGrantsForMutation for why this locks per library
    // via an advisory lock before counting, rather than row-locking the grants directly.
    if (grant.getRole() == AssetRole.OWNER
        && !grant.isExpired(Instant.now())
        && isLastActiveOwnerGrant(library.getId(), grant.getId())) {
      throw new ConflictException(
          "Die letzte "
              + roleLabel(AssetRole.OWNER)
              + "-Berechtigung einer Bibliothek kann nicht"
              + " entfernt werden");
    }

    // #238/#892: published before the row is gone - GrantChanged's listeners read the grant's
    // last-active role/expiresAt off this same entity for both the history and the audit write.
    eventPublisher.publishEvent(
        new GrantChanged(
            library,
            grant,
            GrantChanged.Cause.REVOKED,
            currentUserId,
            grantAuditPayload(grant.getRole(), grant.getExpiresAt()),
            null));
    grantRepository.delete(grant);
    invalidateAfterCommit(library.getId());
  }

  /**
   * Escalation guard, half 2 (see the class Javadoc): whether {@code callerRole} is at least as
   * privileged as the role an existing grant already carries, before that grant may be changed or
   * removed. {@code action} is the German verb ("ändern"/"entfernen") for the resulting message.
   */
  private void requireCallerCanTouchExistingGrant(
      AssetRole callerRole, AssetGrant existingGrant, String action) {
    requireCallerRoleAtLeast(
        callerRole,
        existingGrant.getRole(),
        "Die eigene Rolle reicht nicht aus, um eine bestehende "
            + roleLabel(existingGrant.getRole())
            + "-Berechtigung zu "
            + action);
  }

  /**
   * The German role label shown in user-facing messages, analogous to {@code assetRoleLabel} in
   * {@code frontend/src/utils/labels.ts} - kept separate rather than shared, since frontend and
   * backend are distinct build artifacts (#448). Every {@link AssetRole} value must be mapped here;
   * an unmapped value would otherwise leak the raw English enum name into a German-language
   * message.
   */
  private static String roleLabel(AssetRole role) {
    return switch (role) {
      case VIEWER -> "Betrachter";
      case EDITOR -> "Bearbeiter";
      case MANAGER -> "Verwalter";
      case OWNER -> "Eigentümer";
    };
  }

  /**
   * Throws {@code 403} unless {@code callerRole} is at least as privileged as {@code otherRole}.
   */
  private void requireCallerRoleAtLeast(AssetRole callerRole, AssetRole otherRole, String message) {
    if (callerRole == null || otherRole.ordinal() > callerRole.ordinal()) {
      throw new AccessDeniedException(message);
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
      throw new ConflictException(
          "Die letzte "
              + roleLabel(AssetRole.OWNER)
              + "-Berechtigung einer Bibliothek kann nicht"
              + " herabgestuft werden");
    }
  }

  private KnowledgeLibrary requireManageable(UUID libraryId, CurrentUser caller) {
    KnowledgeLibrary library =
        libraryRepository
            .findById(libraryId)
            .orElseThrow(() -> new NotFoundException("Bibliothek nicht gefunden"));
    if (!library.getOrganizationId().equals(caller.organizationId())) {
      throw new NotFoundException("Bibliothek nicht gefunden");
    }
    // #436: no access at all (no grant, no organization-wide visibility) also answers 404, not just
    // the organization-boundary case above - see LibraryAccessService#requireRole.
    accessService.requireRole(library, caller.id(), caller.isSystemAdmin(), AssetRole.MANAGER);
    return library;
  }

  private User requireUser(UUID userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(() -> new NotFoundException("Benutzer nicht gefunden"));
  }

  private void requireUserInOrganization(UUID userId, UUID organizationId) {
    User user = requireUser(userId);
    if (!user.getOrganizationId().equals(organizationId)) {
      throw new NotFoundException("Benutzer nicht gefunden");
    }
  }

  /**
   * Resolves a group, enforces the organization boundary, and rejects a dissolved group as a grant
   * target - see the class Javadoc for why: existing grants to a dissolved group keep working (see
   * {@link LibraryAccessService#effectiveRole}, which does not check {@link Group#isDissolved()}
   * either), but no new or updated grant may target it.
   *
   * <p>Package-private (not {@code private}) so {@link KnowledgeLibraryService#createLibrary} can
   * reuse the same check for the initial owner-group grant of a group-owned library, instead of
   * duplicating the {@link Group#isDissolved()} check outside this service (#441).
   */
  void requireGrantableGroup(UUID groupId, UUID organizationId) {
    Group group =
        groupRepository
            .findById(groupId)
            .orElseThrow(() -> new NotFoundException("Gruppe nicht gefunden"));
    if (!group.getOrganizationId().equals(organizationId)) {
      throw new NotFoundException("Gruppe nicht gefunden");
    }
    if (group.isDissolved()) {
      throw new ValidationException(
          "Die Gruppe ist aufgelöst und kann keine neuen Berechtigungen mehr erhalten");
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

  /**
   * Resolves {@code subjectDisplayName} and {@code grantedByDisplayName} server-side (#423 code
   * review, "Namensauflösung scheitert genau bei der Rolle, für die das Issue gebaut wird"): {@code
   * GET /v1/admin/users} and {@code GET /v1/admin/groups}, which the frontend used to resolve these
   * names client-side, both require {@code SYSTEM_ADMIN} - exactly the callers this endpoint's own
   * {@code MANAGER} threshold is meant to admit without one. The backend already holds both
   * repositories for grant validation, so resolving names here needs no new dependency and works
   * for every caller {@link #requireManageable} lets through.
   *
   * <p>Batches the lookup across the whole list in two queries (one per subject kind, plus granters
   * folded into the user query) rather than one lookup per grant, since {@link #listGrants} is the
   * expected caller and a per-grant round trip would turn an O(1)-query list into O(n).
   */
  private List<AssetGrantView> toViews(List<AssetGrant> grants) {
    Set<UUID> userIds = new HashSet<>();
    Set<UUID> groupIds = new HashSet<>();
    for (AssetGrant grant : grants) {
      if (grant.getSubjectType() == PermissionSubjectType.USER) {
        userIds.add(grant.getSubjectId());
      } else {
        groupIds.add(grant.getSubjectId());
      }
      if (grant.getGrantedByUserId() != null) {
        userIds.add(grant.getGrantedByUserId());
      }
    }
    Map<UUID, String> userNames = new HashMap<>();
    for (User user : userRepository.findAllById(userIds)) {
      // #446 code review round 2: displayName is nullable (a token without a name/
      // preferred_username claim leaves it unset - see UserService#findOrCreateUser, which only
      // overwrites it when the incoming claim is non-null and otherwise leaves whatever was there,
      // including nothing on first login) - falling back to the raw id would reintroduce the same
      // "MANAGER sees a UUID" gap this method exists to close. email is required and always
      // present on a persisted User, so it is the last resort before the id itself.
      String name = user.getDisplayName() != null ? user.getDisplayName() : user.getEmail();
      userNames.put(user.getId(), name);
    }
    Map<UUID, String> groupNames = new HashMap<>();
    for (Group group : groupRepository.findAllById(groupIds)) {
      groupNames.put(group.getId(), group.getName());
    }

    return grants.stream()
        .map(
            grant -> {
              String subjectName =
                  grant.getSubjectType() == PermissionSubjectType.USER
                      ? userNames.get(grant.getSubjectId())
                      : groupNames.get(grant.getSubjectId());
              String grantedByName =
                  grant.getGrantedByUserId() == null
                      ? null
                      : userNames.get(grant.getGrantedByUserId());
              return new AssetGrantView(grant, subjectName, grantedByName);
            })
        .toList();
  }
}
