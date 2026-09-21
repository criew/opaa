package io.opaa.space;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.opaa.api.types.AccessBasis;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.Capability;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SpaceVisibility;
import io.opaa.api.types.SuccessionObjectType;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.chat.ChatRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.OrganizationScopedLoader;
import io.opaa.common.ValidationException;
import io.opaa.permission.AccessPath;
import io.opaa.permission.AssetOwnershipHistoryService;
import io.opaa.permission.CapabilityService;
import io.opaa.permission.GroupAttribution;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.GroupSizeProperties;
import io.opaa.permission.GroupSubject;
import io.opaa.permission.GroupSubjectDirectory;
import io.opaa.permission.PermissionSubject;
import io.opaa.permission.SuccessionReachGuard;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Transactional(readOnly = true)
public class SpaceService {

  private static final int MAX_NAME_LENGTH = 255;
  private static final int MAX_DESCRIPTION_LENGTH = 2000;

  private final SpaceRepository spaceRepository;
  private final UserRepository userRepository;
  private final AuditEventRecorder auditEventRecorder;
  private final ChatRepository chatRepository;
  private final SpaceAssetAssociationService associationService;
  private final SpaceAccessPolicy accessPolicy;
  private final SpaceMembershipHistoryService membershipHistory;
  private final AssetOwnershipHistoryService ownershipHistory;
  private final GroupMembershipResolver groupMemberships;
  private final GroupSubjectDirectory groupDirectory;
  private final CapabilityService capabilityService;
  private final GroupSizeProperties groupSizeProperties;
  private final SuccessionReachGuard successionGuard;
  private final TransactionTemplate requiresNewTransactionTemplate;

  /**
   * Caches "this user already has a personal space" so that every login after the first no longer
   * needs {@link SpaceRepository#existsByOwnerIdAndIsDefaultTrue} at all. A default space is never
   * deleted (see {@link #deleteSpace}'s and {@link #archiveSpace}'s guard), so once true this fact
   * never goes stale - no TTL needed, only a size bound against unbounded growth.
   */
  private final Cache<UUID, Boolean> personalSpaceProvisioned;

  public SpaceService(
      SpaceRepository spaceRepository,
      UserRepository userRepository,
      AuditEventRecorder auditEventRecorder,
      ChatRepository chatRepository,
      SpaceAssetAssociationService associationService,
      SpaceAccessPolicy accessPolicy,
      SpaceMembershipHistoryService membershipHistory,
      AssetOwnershipHistoryService ownershipHistory,
      GroupMembershipResolver groupMemberships,
      GroupSubjectDirectory groupDirectory,
      CapabilityService capabilityService,
      GroupSizeProperties groupSizeProperties,
      SuccessionReachGuard successionGuard,
      PlatformTransactionManager transactionManager) {
    this.spaceRepository = spaceRepository;
    this.successionGuard = successionGuard;
    this.chatRepository = chatRepository;
    this.userRepository = userRepository;
    this.auditEventRecorder = auditEventRecorder;
    this.associationService = associationService;
    this.accessPolicy = accessPolicy;
    this.membershipHistory = membershipHistory;
    this.ownershipHistory = ownershipHistory;
    this.groupMemberships = groupMemberships;
    this.groupDirectory = groupDirectory;
    this.capabilityService = capabilityService;
    this.groupSizeProperties = groupSizeProperties;
    this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
    this.requiresNewTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    // Same bound as GroupMembershipResolver's per-user cache; unlike that one this needs no
    // expireAfterWrite, see the field Javadoc above.
    this.personalSpaceProvisioned = Caffeine.newBuilder().maximumSize(50_000).build();
  }

  @Transactional
  public Space createSpace(SpaceCreation creation, CurrentUser caller) {
    // #333 removed SpaceKind: a caller holding CREATE_SPACE may create any number of spaces,
    // including ones they work in alone. Only the default space is special: it is provisioned at
    // first sign-in and therefore independent of this capability - see ensureDefaultSpace.
    capabilityService.requireCapability(caller, Capability.CREATE_SPACE);
    UUID ownerId = creation.ownerId() != null ? creation.ownerId() : caller.id();
    if (!caller.isSystemAdmin() && !ownerId.equals(caller.id())) {
      throw new AccessDeniedException(
          "Nur Systemadministratoren können beim Erstellen einen anderen Eigentümer festlegen");
    }
    if (!ownerId.equals(caller.id())) {
      // The organization boundary is checked even for system admins - a user from another
      // organization must not become owner of a space in this one.
      requireUserInOrganization(ownerId, caller.organizationId());
    }

    SpaceVisibility visibility =
        creation.visibility() != null ? creation.visibility() : SpaceVisibility.PRIVATE;

    Space space =
        buildValidatedSpace(
            creation.name(),
            creation.description(),
            false,
            visibility,
            ownerId,
            caller.organizationId());
    appendInitialMemberships(space, ownerId, creation.initialMembers());

    Space saved = spaceRepository.save(space);
    for (SpaceMembership membership : saved.getMemberships()) {
      membershipHistory.recordAdded(membership, caller.id());
    }
    ownershipHistory.recordCreated(
        Space.ASSET_TYPE,
        saved.getId(),
        PermissionSubject.user(saved.getOwnerId(), saved.getOrganizationId()),
        caller.id());
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(saved.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.SPACE_CREATED)
            .object(AuditObjectType.SPACE, saved.getId(), saved.getName())
            .after(spaceAuditPayload(saved))
            .outcome(AuditOutcome.SUCCESS)
            .build());

    // #686/#706 review: associated in the same transaction as the space itself, not in a
    // best-effort loop at the controller - a library that cannot be associated (not found, or not
    // readable by the creator) rolls the whole creation back rather than leaving a half-created
    // space behind. associationService.associate participates in this method's own transaction
    // (default REQUIRES propagation on a Spring-managed bean call), so a failure here rolls back
    // both the space row and every association already inserted for it.
    if (creation.libraryIds() != null) {
      for (UUID libraryId : creation.libraryIds()) {
        associationService.associate(saved.getId(), libraryId, caller);
      }
    }

    return saved;
  }

  private Map<String, Object> spaceAuditPayload(Space space) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("name", space.getName());
    payload.put("visibility", space.getVisibility().name());
    payload.put("ownerId", space.getOwnerId().toString());
    return payload;
  }

  public List<SpaceOverview> listSpaces(CurrentUser caller) {
    Set<UUID> callerGroupIds = groupMemberships.groupIdsForUser(caller.id());
    // Two queries rather than one disjunction: a caller who belongs to no group must not send an
    // empty IN list, and the union is by entity identity within the same persistence context.
    Map<UUID, Space> reachable = new LinkedHashMap<>();
    for (Space space :
        spaceRepository.findDistinctByMembershipsUserIdWithMemberships(caller.id())) {
      reachable.put(space.getId(), space);
    }
    if (!callerGroupIds.isEmpty()) {
      for (Space space :
          spaceRepository.findDistinctByMembershipGroupIdsWithMemberships(callerGroupIds)) {
        reachable.putIfAbsent(space.getId(), space);
      }
    }
    List<Space> memberSpaces =
        reachable.values().stream()
            .filter(space -> space.getOrganizationId().equals(caller.organizationId()))
            .toList();
    List<UUID> spaceIds = memberSpaces.stream().map(Space::getId).toList();
    // #682: the overview card's figures ("n Quellen · n Chats · n Mitglieder") come from two
    // grouped queries for the whole list, never one lookup per space. The chat figure counts the
    // caller's own chats only (#525) - which is exactly the "has a chat of their own" question
    // the #543 archived-space rule below asks, so it answers that too.
    Map<UUID, Long> chatCounts = ownChatCounts(spaceIds, caller.id());
    Map<UUID, Long> libraryCounts = associationService.countVisibleBySpace(memberSpaces, caller);
    return memberSpaces.stream()
        // #543: an archived space is left out of this list unless the caller has a chat of their
        // own in it, is the space's owner, or is a system admin - otherwise, in the typical #543
        // case where the owner has no chat of their own in the space they archived, the space
        // would vanish from their own list with no way back (#613 review, finding 3: no unarchive
        // endpoint exists, so this is the only way the owner ever sees it again).
        .filter(
            space ->
                !space.isArchived()
                    || caller.isSystemAdmin()
                    || space.getOwnerId().equals(caller.id())
                    || chatCounts.getOrDefault(space.getId(), 0L) > 0)
        .map(
            space ->
                new SpaceOverview(
                    space,
                    libraryCounts.getOrDefault(space.getId(), 0L).intValue(),
                    chatCounts.getOrDefault(space.getId(), 0L).intValue(),
                    SpaceAccessPolicy.effectiveRole(space, caller.id(), callerGroupIds),
                    !accessPolicy.hasCapableAdmin(space)))
        .toList();
  }

  private Map<UUID, Long> ownChatCounts(List<UUID> spaceIds, UUID authorId) {
    if (spaceIds.isEmpty()) {
      return Map.of();
    }
    return chatRepository.countBySpaceIdInAndAuthorId(spaceIds, authorId).stream()
        .collect(
            Collectors.toMap(
                ChatRepository.SpaceChatCount::getSpaceId,
                ChatRepository.SpaceChatCount::getChatCount));
  }

  /**
   * The space with the two derived values a response shows: the caller's effective role and
   * "Nachfolge offen" (ADR-0036, Entscheidung 6). Kept in this package rather than computed in the
   * controller, so the mapper stays a pure entity-to-response step (AGENTS.md, API-Konvention).
   */
  public SpaceDetail detailOf(Space space, CurrentUser caller) {
    return new SpaceDetail(
        space, accessPolicy.effectiveRole(space, caller), !accessPolicy.hasCapableAdmin(space));
  }

  public Space getSpace(UUID spaceId, CurrentUser caller) {
    Space space = loadSpace(spaceId, caller);

    accessPolicy.requireMember(space, caller);

    return space;
  }

  public List<SpaceMemberView> listMembers(UUID spaceId, CurrentUser caller) {
    Space space = loadSpace(spaceId, caller);
    // #144: the member list names every member of the space - who else works in "Disziplinar-
    // verfahren" or "Umstrukturierung Abteilung 3" is itself sensitive. Unlike getSpace, which only
    // checks membership, this is restricted to ADMIN, the owner and system admins - see
    // SpaceAccessPolicy#requireMemberListViewer. A group row is therefore named only to those who
    // manage its membership here (ADR-0036, Entscheidung 9).
    if (!caller.isSystemAdmin()) {
      accessPolicy.requireMemberListViewer(space, caller);
    }

    List<UUID> userIds =
        space.getMemberships().stream()
            .filter(SpaceMembership::isUserSubject)
            .map(SpaceMembership::getUserId)
            .toList();
    Map<UUID, String> displayNames = resolveDisplayNames(userIds);
    List<UUID> groupIds =
        space.getMemberships().stream()
            .filter(SpaceMembership::isGroupSubject)
            .map(SpaceMembership::getGroupId)
            .toList();
    Map<UUID, String> groupNames =
        groupIds.isEmpty() ? Map.of() : groupDirectory.namesById(groupIds);

    return space.getMemberships().stream()
        .map(
            membership ->
                membership.isUserSubject()
                    ? SpaceMemberView.ofUser(membership, displayNames.get(membership.getUserId()))
                    : new SpaceMemberView(
                        membership,
                        groupNames.get(membership.getGroupId()),
                        groupSizeSignal(membership)))
        .toList();
  }

  /**
   * The space context of a Rechteprofil-Lauf (#1835, ADR-0036 Entscheidung 7) - the associated
   * libraries and the size of the group's reach into this space, both read at the moment of the
   * run. The caller decides what to do with the figure; this method takes no rights decision, which
   * is why it is readable for the system administration alone through its one caller.
   *
   * <p>Reach is counted on <b>every</b> path: an own membership, a membership of any group the
   * person belongs to, and ownership. Counted against "is in this group" it would be zero for a
   * group that is itself no space member - structurally, not by configuration.
   */
  public SpaceGroupContext spaceGroupContext(UUID spaceId, UUID groupId, CurrentUser caller) {
    Space space = loadSpace(spaceId, caller);
    Set<UUID> groupMembers = groupMemberships.activeMemberIds(groupId, caller.organizationId());

    Set<UUID> reaching = new java.util.HashSet<>();
    reaching.add(space.getOwnerId());
    for (SpaceMembership membership : space.getMemberships()) {
      if (membership.isUserSubject()) {
        reaching.add(membership.getUserId());
      } else {
        reaching.addAll(
            groupMemberships.activeMemberIds(membership.getGroupId(), space.getOrganizationId()));
      }
    }
    long reach = groupMembers.stream().filter(reaching::contains).count();

    return new SpaceGroupContext(
        space.getId(),
        space.getName(),
        associationService.libraryIdsInSpace(space.getId()),
        Math.toIntExact(reach));
  }

  /**
   * The Herleitung "warum bin ich in diesem Space" (#1822, ADR-0036 Entscheidung 9). {@code
   * targetUserId} null asks about the caller; naming somebody else is reserved for those who manage
   * the membership here - the same bar {@link #listMembers} carries - and hides every way through a
   * protected group. A person this space does not reach at all is 404, like an unknown space: an
   * empty answer would confirm both the space and the absence.
   */
  public SpaceAccessDerivation accessDerivation(
      UUID spaceId, UUID targetUserId, CurrentUser caller) {
    Space space = loadSpace(spaceId, caller);
    boolean thirdParty = targetUserId != null && !targetUserId.equals(caller.id());
    if (thirdParty && !caller.isSystemAdmin()) {
      accessPolicy.requireMemberListViewer(space, caller);
    } else if (!thirdParty) {
      accessPolicy.requireMember(space, caller);
    }

    UUID subjectId = targetUserId == null ? caller.id() : targetUserId;
    Set<UUID> groupIds = groupMemberships.groupIdsForUser(subjectId);
    SpaceRole effectiveRole = SpaceAccessPolicy.effectiveRole(space, subjectId, groupIds);

    List<SpaceMembership> reaching =
        space.getMemberships().stream()
            .filter(
                membership ->
                    membership.isUserSubject()
                        ? subjectId.equals(membership.getUserId())
                        : groupIds.contains(membership.getGroupId()))
            .toList();
    Map<UUID, GroupAttribution> attributions =
        groupDirectory.attributionsById(
            reaching.stream()
                .filter(SpaceMembership::isGroupSubject)
                .map(SpaceMembership::getGroupId)
                .toList());

    List<AccessPath> paths = new ArrayList<>();
    boolean withheld = false;
    if (space.getOwnerId().equals(subjectId)) {
      paths.add(AccessPath.ofSpace(AccessBasis.OWNERSHIP, SpaceRole.ADMIN, null, null));
    }
    for (SpaceMembership membership : reaching) {
      if (membership.isUserSubject()) {
        paths.add(
            AccessPath.ofSpace(
                AccessBasis.DIRECT_MEMBERSHIP,
                membership.getRole(),
                membership.getCreatedAt(),
                null));
        continue;
      }
      GroupAttribution group = attributions.get(membership.getGroupId());
      if (thirdParty && group != null && group.protectedGroup()) {
        withheld = true;
        continue;
      }
      paths.add(
          AccessPath.ofSpace(
              AccessBasis.GROUP_MEMBERSHIP,
              membership.getRole(),
              membership.getCreatedAt(),
              group));
    }
    if (paths.isEmpty() && !withheld) {
      if (thirdParty || !caller.isSystemAdmin()) {
        throw new NotFoundException("Space nicht gefunden");
      }
      paths.add(AccessPath.ofSpace(AccessBasis.SYSTEM_ADMINISTRATION, null, null, null));
    }
    return new SpaceAccessDerivation(
        space.getId(), subjectId, effectiveRole, List.copyOf(paths), withheld);
  }

  /**
   * The growth signal of a group membership (ADR-0036, Entscheidung 9), with the "kleine Gruppe"
   * suppression applied - see {@link GroupSizeSignal}.
   */
  private GroupSizeSignal groupSizeSignal(SpaceMembership membership) {
    return GroupSizeSignal.of(
        membership.getMemberCountAtGrant(),
        groupMemberships.activeMemberCount(membership.getGroupId(), membership.getOrganizationId()),
        groupSizeProperties.minimumGroupSize());
  }

  /**
   * Admits a person or a group to the space (#1815, ADR-0036 Entscheidung 6). A group's members
   * hold the role without a row of their own; only an <em>effective</em> group may be admitted, and
   * an effective but empty one may - with the warning the response carries.
   */
  @Transactional
  public SpaceMemberView addMember(
      UUID spaceId, PermissionSubject subject, SpaceRole requestedRole, CurrentUser caller) {
    Space space = loadSpace(spaceId, caller);
    // ADR-0036, Entscheidung 6: a space without a capable ADMIN takes no new members while its
    // succession is open - everything else about it keeps working.
    successionGuard.requireReachNotFrozen(
        SuccessionObjectType.SPACE, space.getId(), "Die Aufnahme eines neuen Mitglieds");
    accessPolicy.requireManager(space, caller);
    // #613 review, finding 2: an archived space accepts no new content, and a new member is new
    // content in the sense the specification means - see docs/features/spaces-and-assets.md#einen-
    // space-stilllegen-archivieren-statt-löschen ("keine neuen Chats, Nachrichten, Umbenennungen
    // oder Mitglieder").
    requireNotArchived(space);
    if (membershipOf(space, subject) != null) {
      throw new ConflictException(
          subject.type() == PermissionSubjectType.USER
              ? "Der Benutzer ist bereits Mitglied dieses Space"
              : "Die Gruppe ist bereits Mitglied dieses Space");
    }

    SpaceRole roleToAssign = requestedRole == null ? SpaceRole.MEMBER : requestedRole;
    SpaceMembership membership =
        subject.type() == PermissionSubjectType.USER
            ? addUserMembership(space, subject.id(), roleToAssign)
            : addGroupMembership(space, subject.id(), roleToAssign, caller);
    spaceRepository.save(space);
    membershipHistory.recordAdded(membership, caller.id());
    auditEventRecorder.recordUserActionOnSubject(
        AuditEvent.builder()
            .organizationId(space.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.SPACE_MEMBER_ADDED)
            .object(AuditObjectType.SPACE, space.getId(), space.getName())
            .subject(auditSubjectKind(membership), membership.subjectId())
            .after(Map.of("role", roleToAssign.name()))
            .outcome(AuditOutcome.SUCCESS)
            .build());

    return toView(membership);
  }

  private SpaceMembership addUserMembership(Space space, UUID memberUserId, SpaceRole role) {
    // Resolving the target user first also turns a non-existent userId into a clean 404 instead
    // of a raw foreign-key violation from the membership insert below.
    requireUserInOrganization(memberUserId, space.getOrganizationId());
    SpaceMembership membership =
        SpaceMembership.ofUser(memberUserId, role, space.getOrganizationId());
    space.addMembership(membership);
    return membership;
  }

  /**
   * Only an effective group becomes a new space member (ADR-0036, Entscheidung 6, Schutzregel 4):
   * neither dissolved, nor belonging to a switched-off identity provider, nor a token group whose
   * provider has since switched to the directory run (#1816) - otherwise the membership would reach
   * nobody now and, with the provider switched back on, everybody at once without a second
   * decision, or it would sit on a membership that is frozen for good. Same three reasons {@code
   * AssetGrantService#requireGrantableGroup} refuses a grant for; "wirksam" is one notion. An
   * <em>empty</em> effective group is admitted on purpose: otherwise "create the group, admit it,
   * then fill it" failed at the first step, and a group that only comes into existence with the
   * first sign-in ({@code TokenGroupSynchronizer#findOrCreate}) could never be the target of a
   * provider changeover.
   *
   * <p>An internal group its stewards have not released answers like an unknown one (ADR-0036,
   * Entscheidung 9) - on this path as on every other, including the id typed by hand, because in
   * the selection list alone the rule would be cosmetics.
   */
  private SpaceMembership addGroupMembership(
      Space space, UUID groupId, SpaceRole role, CurrentUser caller) {
    GroupSubject group =
        groupDirectory
            .find(groupId)
            .filter(found -> found.organizationId().equals(space.getOrganizationId()))
            .orElseThrow(() -> new NotFoundException("Gruppe nicht gefunden"));
    if (!groupDirectory.isSelectableBy(groupId, caller.id(), caller.isSystemAdmin())) {
      throw new NotFoundException("Gruppe nicht gefunden");
    }
    if (group.dissolved()) {
      throw new ConflictException(
          "Die Gruppe ist aufgelöst und kann nicht mehr Mitglied eines Space werden");
    }
    if (group.providerDisabled()) {
      throw new ConflictException(
          "Der Identitätsanbieter dieser Gruppe ist deaktiviert. Sie kann nicht Mitglied eines"
              + " Space werden, solange er es bleibt; bestehende Mitgliedschaften bleiben"
              + " unverändert.");
    }
    if (group.unmaintained()) {
      throw new ConflictException(
          "Diese Gruppe stammt aus dem Gruppen-Claim eines Anbieters, der inzwischen über den"
              + " Verzeichnisabgleich gepflegt wird. Ihre Mitgliedschaft ist eingefroren, deshalb"
              + " kann sie nicht mehr Mitglied eines Space werden; bestehende Mitgliedschaften"
              + " bleiben unverändert. Nehmen Sie die entsprechende Organisationseinheit auf.");
    }
    SpaceMembership membership =
        SpaceMembership.ofGroup(
            groupId,
            role,
            groupMemberships.activeMemberCount(groupId, space.getOrganizationId()),
            space.getOrganizationId());
    space.addMembership(membership);
    return membership;
  }

  @Transactional
  public SpaceMemberView updateMemberRole(
      UUID spaceId, UUID membershipId, SpaceRole newRole, CurrentUser caller) {
    Space space = loadSpace(spaceId, caller);
    accessPolicy.requireManager(space, caller);
    if (newRole == null) {
      throw new ValidationException("role ist erforderlich");
    }

    SpaceMembership target = requireMembership(space, membershipId);
    if (isOwnerMembership(space, target) && newRole != SpaceRole.ADMIN) {
      throw new ConflictException(
          "Die Rolle des Eigentümers kann nicht geändert werden; übertragen Sie zuerst die"
              + " Verantwortung");
    }
    requireCapableAdminRemains(space, target, newRole);

    SpaceRole previousRole = target.getRole();
    target.setRole(newRole);
    spaceRepository.save(space);
    membershipHistory.recordRoleChanged(target, caller.id());
    auditEventRecorder.recordUserActionOnSubject(
        AuditEvent.builder()
            .organizationId(space.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.SPACE_MEMBER_ROLE_CHANGED)
            .object(AuditObjectType.SPACE, space.getId(), space.getName())
            .subject(auditSubjectKind(target), target.subjectId())
            .before(Map.of("role", previousRole.name()))
            .after(Map.of("role", newRole.name()))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return toView(target);
  }

  @Transactional
  public void removeMember(UUID spaceId, UUID membershipId, CurrentUser caller) {
    Space space = loadSpace(spaceId, caller);
    accessPolicy.requireManager(space, caller);

    SpaceMembership target = requireMembership(space, membershipId);
    if (isOwnerMembership(space, target)) {
      throw new ConflictException(
          "Der Eigentümer kann nicht entfernt werden; übertragen Sie zuerst die Verantwortung");
    }
    requireCapableAdminRemains(space, target, null);

    membershipHistory.recordRemoved(target, caller.id());
    space.removeMembership(target);
    spaceRepository.save(space);
    auditEventRecorder.recordUserActionOnSubject(
        AuditEvent.builder()
            .organizationId(space.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.SPACE_MEMBER_REMOVED)
            .object(AuditObjectType.SPACE, space.getId(), space.getName())
            .subject(auditSubjectKind(target), target.subjectId())
            .before(Map.of("role", target.getRole().name()))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  /**
   * ADR-0036, Entscheidung 6, Schutzregel 1: a space never loses its last capable {@code ADMIN}
   * member through a management action - removing, downgrading or leaving is refused with 409, not
   * the 400 the pre-#1815 owner protection used. A group counts as {@code ADMIN} while it is
   * capable of acting.
   *
   * <p><b>Today this check never fires</b>, and the reason belongs here rather than in a review
   * thread: a space always has an owner, the owner is always a member, and until #1818 gives
   * accounts a state every person is capable - so the owner's own row is always a capable {@code
   * ADMIN}, and the only reachable instance of the rule is the owner's own removal or downgrade,
   * which the two explicit guards above refuse first (with the same 409 the ADR's nit asks for).
   * The check is nevertheless the structural home of the rule: ADR-0036, Schutzregel 2 names the
   * account lock as the one action allowed to create the state, so #1818 activates it without
   * touching this call site. Its group half is exercised directly in {@code SpaceAccessPolicyTest}.
   */
  private void requireCapableAdminRemains(Space space, SpaceMembership changed, SpaceRole newRole) {
    if (!accessPolicy.hasCapableAdminAfter(space, changed, newRole)) {
      throw new ConflictException(
          "Der Space verlöre damit sein letztes handlungsfähiges ADMIN-Mitglied. Bestimmen Sie"
              + " zuerst eine Nachfolge.");
    }
  }

  /**
   * Hands the space over to another member (#1815, ADR-0036 Entscheidung 6). <b>Behaviour change:
   * every capable {@code ADMIN} member that is a natural person may do this</b> - to themselves or
   * to another such member - where before only the owner or a system administrator could. A group
   * never becomes owner: "wirksam" is defined for groups alone and is the wrong yardstick here, and
   * the space owner stays a natural person.
   *
   * <p><b>The two sides are not symmetric, on purpose.</b> The caller only needs the ADMIN role,
   * which they may hold through a group. The <em>target</em> needs a membership row of their own:
   * an owner who appears in the member list only through a group would be a responsible party the
   * list does not name, and removing that group would silently take the owner's own membership with
   * it.
   */
  @Transactional
  public void transferOwnership(UUID spaceId, UUID newOwnerUserId, CurrentUser caller) {
    Space space = loadSpace(spaceId, caller);
    if (!caller.isSystemAdmin() && !accessPolicy.hasAtLeast(space, caller.id(), SpaceRole.ADMIN)) {
      throw new AccessDeniedException(
          "Nur ein handlungsfähiges ADMIN-Mitglied oder ein Systemadministrator kann die"
              + " Verantwortung übertragen");
    }

    SpaceMembership newOwnerMembership = userMembership(space, newOwnerUserId);
    if (newOwnerMembership == null) {
      throw new NotFoundException("Der ausgewählte Benutzer ist kein Mitglied dieses Space");
    }

    UUID previousOwnerId = space.getOwnerId();
    space.transferOwnershipTo(newOwnerUserId);
    spaceRepository.save(space);
    ownershipHistory.recordTransferred(
        Space.ASSET_TYPE,
        space.getId(),
        PermissionSubject.user(newOwnerUserId, space.getOrganizationId()),
        caller.id());
    // #392 code review: ASSET_OWNER_CHANGED is in the closed list without a library-only
    // restriction, and the spec's "Eigentuemerwechsel" line sits in the "Spaces, Bibliotheken und
    // Gruppen" block, not a library-specific one - a space ownership transfer belongs under this
    // event type, not the generic SPACE_CHANGED (which would hide it from a filter on
    // event_type = ASSET_OWNER_CHANGED).
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(space.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.ASSET_OWNER_CHANGED)
            .object(AuditObjectType.SPACE, space.getId(), space.getName())
            .before(Map.of("ownerId", previousOwnerId.toString()))
            .after(Map.of("ownerId", newOwnerUserId.toString()))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  @Transactional
  public Space updateSpace(UUID spaceId, SpaceUpdate update, CurrentUser caller) {
    Space space = loadSpace(spaceId, caller);

    if (!caller.isSystemAdmin() && !accessPolicy.hasAtLeast(space, caller.id(), SpaceRole.ADMIN)) {
      throw new AccessDeniedException(
          "Nur Administratoren oder der Eigentümer können einen Space ändern");
    }

    String normalizedName = validateName(update.name());
    validateDescription(update.description());
    String previousName = space.getName();
    String previousDescription = space.getDescription();
    SpaceVisibility previousVisibility = space.getVisibility();
    space.updateDetails(normalizedName, update.description(), update.visibility());
    Space updated = spaceRepository.save(space);
    boolean nameChanged = !Objects.equals(previousName, updated.getName());
    boolean descriptionChanged = !Objects.equals(previousDescription, updated.getDescription());
    boolean visibilityChanged = previousVisibility != updated.getVisibility();
    if (nameChanged || descriptionChanged || visibilityChanged) {
      // #392 code review, finding 4: before/after are limited to what the specification calls
      // "rechtlich Erheblich" - visibility is (it feeds who can see the space), free-text
      // name/description content is not, and is never written here even though it changed;
      // changedFields names which of the three changed without carrying either value. Only
      // visibility, the one field that is itself rights-relevant, carries its actual before/after.
      List<String> changedFields = new ArrayList<>();
      if (nameChanged) {
        changedFields.add("name");
      }
      if (descriptionChanged) {
        changedFields.add("description");
      }
      Map<String, Object> before = new LinkedHashMap<>();
      Map<String, Object> after = new LinkedHashMap<>();
      before.put("changedFields", changedFields);
      after.put("changedFields", changedFields);
      if (visibilityChanged) {
        changedFields.add("visibility");
        before.put("visibility", previousVisibility.name());
        after.put("visibility", updated.getVisibility().name());
      }
      auditEventRecorder.recordUserAction(
          AuditEvent.builder()
              .organizationId(updated.getOrganizationId())
              .actor(caller.id())
              .type(AuditEventType.SPACE_CHANGED)
              .object(AuditObjectType.SPACE, updated.getId(), updated.getName())
              .before(before)
              .after(after)
              .outcome(AuditOutcome.SUCCESS)
              .build());
    }
    return updated;
  }

  @Transactional
  public void deleteSpace(UUID spaceId, CurrentUser caller) {
    Space space = loadSpace(spaceId, caller);

    if (space.isDefault()) {
      throw new ValidationException("Der Standard-Space kann nicht gelöscht werden");
    }

    boolean owner = space.getOwnerId().equals(caller.id());
    if (!caller.isSystemAdmin() && !owner) {
      throw new AccessDeniedException(
          "Nur der Eigentümer oder ein Systemadministrator kann einen Space löschen");
    }

    // #525: chats are composition, not association - docs/features/spaces-and-assets.md#chats-
    // sind-vor-fremder-löschung-geschützt says a chat "bleibt für seinen Autor und im Nachweis
    // erhalten", so deleting the space they live in must not silently destroy them.
    // fk_chats_space_organization is ON DELETE RESTRICT (migration 032, composite as of migration
    // 047) and would reject this anyway, but a raw constraint violation surfaces as an opaque 500 -
    // this check turns it into an understandable 409 instead.
    if (chatRepository.existsBySpaceId(spaceId)) {
      throw new ConflictException(
          "Der Space enthält noch Chats und kann deshalb nicht gelöscht werden. Archivieren Sie"
              + " den Space stattdessen.");
    }

    // space_id and asset_id carry no foreign key on the two history tables (ADR-0016), so the
    // cascade below never closes their open intervals - a deleted space would keep reporting
    // current members and a current owner forever.
    for (SpaceMembership membership : space.getMemberships()) {
      membershipHistory.recordSpaceDeleted(membership, caller.id());
    }
    ownershipHistory.recordAssetDeleted(
        Space.ASSET_TYPE,
        space.getId(),
        PermissionSubject.user(space.getOwnerId(), space.getOrganizationId()),
        caller.id());
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(space.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.SPACE_DELETED)
            .object(AuditObjectType.SPACE, space.getId(), space.getName())
            .before(spaceAuditPayload(space))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    spaceRepository.delete(space);
  }

  /**
   * Archives a space (docs/features/spaces-and-assets.md#einen-space-stilllegen-archivieren-statt-
   * löschen) - the maintainer-decided way out of a space that {@code fk_chats_space_organization}
   * (ON DELETE RESTRICT) makes permanently undeletable because it still contains a chat authored by
   * someone other than the space owner, who cannot even see - let alone delete - that chat
   * themselves. Archiving does not remove that guard or change {@link #deleteSpace}'s behaviour: a
   * real delete remains possible once every chat is actually gone. What it does instead is stop the
   * space from accepting new content ({@code ChatService#createChat}, {@code
   * ChatService#appendTurn}, {@code ChatService#updateChat} and {@link #addMember} all reject with
   * 409) and hide it from {@link #listSpaces} for members without a chat of their own in it - but
   * never for the owner or a system admin, since there is no unarchive endpoint - while every chat,
   * including ones the owner cannot see, stays fully readable for its author.
   *
   * <p>Same permission bar as {@link #deleteSpace}: owner or system admin, and the default space
   * cannot be archived either, for the same reason it cannot be deleted - it is not this user's to
   * retire. Idempotent: archiving an already archived space is a no-op that simply returns its
   * current state, not an error.
   */
  @Transactional
  public Space archiveSpace(UUID spaceId, CurrentUser caller) {
    Space space = loadSpace(spaceId, caller);

    if (space.isDefault()) {
      throw new ValidationException("Der Standard-Space kann nicht archiviert werden");
    }

    boolean owner = space.getOwnerId().equals(caller.id());
    if (!caller.isSystemAdmin() && !owner) {
      throw new AccessDeniedException(
          "Nur der Eigentümer oder ein Systemadministrator kann einen Space archivieren");
    }

    if (space.isArchived()) {
      return space;
    }

    space.archive();
    Space archived = spaceRepository.save(space);
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(archived.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.SPACE_ARCHIVED)
            .object(AuditObjectType.SPACE, archived.getId(), archived.getName())
            .before(spaceAuditPayload(archived))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return archived;
  }

  /**
   * Creates the automatic default space (and its owner {@code ADMIN} membership) for a user if it
   * does not exist yet.
   *
   * <p>Two concurrent first logins of the same user can both pass the {@code existsBy} check below
   * before either has inserted a row - the check alone cannot prevent that. The partial unique
   * index {@code uk_spaces_default_owner} is the actual guard, enforced through {@link
   * SpaceRepository#insertDefaultSpaceIfAbsent}'s {@code ON CONFLICT ... DO NOTHING}: at most one
   * of several concurrent calls for the same owner actually inserts a row, the rest are silent
   * no-ops - never a {@link DataIntegrityViolationException} to catch, and never a second query to
   * re-read the winner's row, because this method returns {@code void}. A genuinely unrelated
   * constraint violation (e.g. a dangling {@code ownerId}) still throws normally, because {@code ON
   * CONFLICT} only ever suppresses the one named partial index, never any other constraint. The
   * single {@code INSERT ... ON CONFLICT} is one round trip regardless of whether it wins or loses
   * the race - confirmed by {@code UserServiceCreationRaceIntegrationTest} and {@code
   * UserServiceConcurrentDistinctUserLoginIntegrationTest} passing repeatedly at the production
   * default pool size of 10, not a raised test-only pool size (raising the pool was deliberately
   * rejected as treating the symptom - see those tests' Javadoc).
   *
   * <p><b>Caller requirement:</b> because the insert runs on its own connection, {@code userId}
   * must already be committed and visible to other connections when this method is called - not
   * merely persisted in a still-open transaction. Calling this from inside the same transaction
   * that first creates the user row will fail with a {@code fk_spaces_owner_organization}
   * violation, because the {@code REQUIRES_NEW} connection cannot see the uncommitted row. {@code
   * PersonalSpaceProvisioner}, the sign-in caller, satisfies this by running as a synchronous
   * {@code UserProvisionedEvent} listener outside any transaction of the publisher.
   *
   * <p><b>{@code Propagation.NOT_SUPPORTED}, overriding the class-level
   * {@code @Transactional(readOnly = true)}:</b> without this override, calling this public method
   * through the Spring proxy opened an ambient read-only transaction (holding one JDBC connection)
   * for this method's entire duration, while {@code requiresNewTransactionTemplate} below opened a
   * <em>second</em>, independent connection for its {@code REQUIRES_NEW} transaction - two
   * connections held by one caller at once, the same class of bug #299 fixed in {@code
   * UserService.findOrCreateUser}. {@code NOT_SUPPORTED} suspends any ambient transaction for this
   * method's duration and leaves only the one connection {@code requiresNewTransactionTemplate}
   * actually needs.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void ensureDefaultSpace(UUID userId, UUID organizationId) {
    // The common case - a returning user who already has a personal space - costs zero pooled
    // connections; see personalSpaceProvisioned's Javadoc.
    if (Boolean.TRUE.equals(personalSpaceProvisioned.getIfPresent(userId))) {
      return;
    }
    if (spaceRepository.existsByOwnerIdAndIsDefaultTrue(userId)) {
      personalSpaceProvisioned.put(userId, Boolean.TRUE);
      return;
    }

    insertDefaultSpace(userId, organizationId);
  }

  /**
   * Same guarantee as {@link #ensureDefaultSpace(UUID, UUID)}, but for a {@code userId} the caller
   * already knows to be brand new - {@code PersonalSpaceProvisioner} calls this only for a
   * subject/issuer pair the sign-in's own insert (not a concurrent winner's) just created. A user
   * row that did not exist a moment ago cannot already own a personal space, so the {@code
   * existsBy} check {@link #ensureDefaultSpace(UUID, UUID)} performs first is guaranteed to return
   * {@code false} here - calling it anyway would spend a whole extra pooled connection confirming a
   * fact already known. Skipping it halves this method's connection consumption to one {@code
   * REQUIRES_NEW} insert instead of an exists check plus an insert.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void ensureDefaultSpaceForNewUser(UUID userId, UUID organizationId) {
    insertDefaultSpace(userId, organizationId);
  }

  private void insertDefaultSpace(UUID userId, UUID organizationId) {
    requiresNewTransactionTemplate.executeWithoutResult(
        status ->
            spaceRepository.insertDefaultSpaceIfAbsent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Meine Dokumente",
                "Privater persönlicher Space",
                userId,
                organizationId));
    personalSpaceProvisioned.put(userId, Boolean.TRUE);
  }

  private Space buildValidatedSpace(
      String name,
      String description,
      boolean isDefault,
      SpaceVisibility visibility,
      UUID ownerId,
      UUID organizationId) {
    String normalizedName = validateName(name);
    validateDescription(description);
    return new Space(normalizedName, description, isDefault, visibility, ownerId, organizationId);
  }

  private String validateName(String name) {
    if (name == null || name.isBlank()) {
      throw new ValidationException("name ist erforderlich");
    }
    String trimmed = name.trim();
    if (trimmed.length() > MAX_NAME_LENGTH) {
      throw new ValidationException("name darf höchstens " + MAX_NAME_LENGTH + " Zeichen umfassen");
    }
    return trimmed;
  }

  private void validateDescription(String description) {
    if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
      throw new ValidationException(
          "description darf höchstens " + MAX_DESCRIPTION_LENGTH + " Zeichen umfassen");
    }
  }

  /**
   * Resolves a user and enforces the organization boundary for it via {@link
   * OrganizationScopedLoader}. Used for every foreign userId that a request body can supply (owner,
   * initial members, added members) - without this, a request could reference a user from another
   * organization and the resulting membership row would silently violate the organization
   * invariant. Returns 404 rather than 403 both when the user does not exist and when it belongs to
   * a different organization, so that a caller cannot distinguish "no such user" from "user in
   * another organization".
   */
  private User requireUserInOrganization(UUID userId, UUID organizationId) {
    return OrganizationScopedLoader.load(
        () -> userRepository.findById(userId),
        User::getOrganizationId,
        organizationId,
        "Benutzer nicht gefunden");
  }

  /**
   * Loads a space and enforces the organization boundary via {@link OrganizationScopedLoader}. A
   * space belonging to a different organization than the caller is treated as not found - the
   * boundary is not overstepped even to reveal existence, and this applies to system administrators
   * as well.
   */
  private Space loadSpace(UUID spaceId, CurrentUser caller) {
    return OrganizationScopedLoader.load(
        () -> spaceRepository.findByIdWithMemberships(spaceId),
        Space::getOrganizationId,
        caller.organizationId(),
        "Space nicht gefunden");
  }

  /**
   * #613 review, finding 2: "kein neuer Inhalt" is not only "no new chats" (already enforced by
   * {@code ChatService#createChat}) - it also covers adding a new member, which is why this is
   * called from {@link #addMember} too. See docs/features/spaces-and-assets.md#einen-space-
   * stilllegen-archivieren-statt-löschen.
   */
  private void requireNotArchived(Space space) {
    if (space.isArchived()) {
      throw new ConflictException(
          "Der Space ist archiviert und lässt keine neuen Mitglieder mehr zu");
    }
  }

  private String resolveDisplayName(UUID userId) {
    return userRepository
        .findById(userId)
        .map(u -> u.getDisplayName() != null ? u.getDisplayName() : u.getEmail())
        .orElse(null);
  }

  private Map<UUID, String> resolveDisplayNames(List<UUID> userIds) {
    Map<UUID, String> result = new HashMap<>();
    for (User user : userRepository.findAllById(userIds)) {
      result.put(
          user.getId(), user.getDisplayName() != null ? user.getDisplayName() : user.getEmail());
    }
    return result;
  }

  private void appendInitialMemberships(
      Space space, UUID ownerId, List<SpaceMemberSeed> initialMembers) {
    Map<UUID, SpaceRole> resolvedRoles = new LinkedHashMap<>();
    if (initialMembers != null) {
      for (SpaceMemberSeed member : initialMembers) {
        if (member == null) {
          continue;
        }
        resolvedRoles.put(member.userId(), member.role());
      }
    }
    resolvedRoles.put(ownerId, SpaceRole.ADMIN);
    resolvedRoles.forEach(
        (userId, role) -> {
          // Every initial member - not just the owner - must belong to the same organization as
          // the space being created; otherwise any user could be added to a space without ever
          // being validated as an admin action, and the membership would violate the
          // organization invariant.
          requireUserInOrganization(userId, space.getOrganizationId());
          space.addMembership(SpaceMembership.ofUser(userId, role, space.getOrganizationId()));
        });
  }

  /** The person's own membership row, if they hold one - never a group row. */
  private SpaceMembership userMembership(Space space, UUID userId) {
    return space.getMemberships().stream()
        .filter(membership -> membership.isUserSubject() && membership.getUserId().equals(userId))
        .findFirst()
        .orElse(null);
  }

  private SpaceMembership membershipOf(Space space, PermissionSubject subject) {
    return space.getMemberships().stream()
        .filter(
            membership ->
                membership.getSubjectType() == subject.type()
                    && membership.subjectId().equals(subject.id()))
        .findFirst()
        .orElse(null);
  }

  /**
   * The membership addressed by its own id, the way a grant is addressed by {@code grantId} - so a
   * person and a group carrying the same id can never be confused, and the caller never has to name
   * the subject type a second time.
   */
  private SpaceMembership requireMembership(Space space, UUID membershipId) {
    return space.getMemberships().stream()
        .filter(membership -> membership.getId().equals(membershipId))
        .findFirst()
        .orElseThrow(() -> new NotFoundException("Mitglied des Space nicht gefunden"));
  }

  private static boolean isOwnerMembership(Space space, SpaceMembership membership) {
    return membership.isUserSubject() && space.getOwnerId().equals(membership.getUserId());
  }

  private static AuditSubjectKind auditSubjectKind(SpaceMembership membership) {
    return membership.isUserSubject() ? AuditSubjectKind.USER : AuditSubjectKind.GROUP;
  }

  private SpaceMemberView toView(SpaceMembership membership) {
    if (membership.isUserSubject()) {
      return SpaceMemberView.ofUser(membership, resolveDisplayName(membership.getUserId()));
    }
    return new SpaceMemberView(
        membership,
        groupDirectory.namesById(List.of(membership.getGroupId())).get(membership.getGroupId()),
        groupSizeSignal(membership));
  }
}
