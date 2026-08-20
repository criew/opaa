package io.opaa.space;

import io.opaa.api.dto.SpaceListResponse;
import io.opaa.api.dto.SpaceMemberRequest;
import io.opaa.api.dto.SpaceMemberResponse;
import io.opaa.api.dto.SpaceRequest;
import io.opaa.api.dto.SpaceResponse;
import io.opaa.api.dto.SpaceUpdateRequest;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.audit.AuditEventType;
import io.opaa.audit.AuditObjectType;
import io.opaa.audit.AuditOutcome;
import io.opaa.audit.AuditSubjectKind;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.chat.ChatRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class SpaceService {

  private static final int MAX_NAME_LENGTH = 255;
  private static final int MAX_DESCRIPTION_LENGTH = 2000;

  private final SpaceRepository spaceRepository;
  private final UserRepository userRepository;
  private final AuditEventRecorder auditEventRecorder;
  private final ChatRepository chatRepository;
  private final TransactionTemplate requiresNewTransactionTemplate;

  public SpaceService(
      SpaceRepository spaceRepository,
      UserRepository userRepository,
      AuditEventRecorder auditEventRecorder,
      ChatRepository chatRepository,
      PlatformTransactionManager transactionManager) {
    this.spaceRepository = spaceRepository;
    this.chatRepository = chatRepository;
    this.userRepository = userRepository;
    this.auditEventRecorder = auditEventRecorder;
    this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
    this.requiresNewTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Transactional
  public SpaceResponse createSpace(SpaceRequest request, UUID currentUserId, boolean systemAdmin) {
    User currentUser = requireUser(currentUserId);

    // #333 removed SpaceKind: every user may create any number of spaces, including ones they work
    // in alone. Only the default space is special, and it is created automatically rather than
    // through this endpoint - see ensureDefaultSpace.
    UUID ownerId = request.getOwnerId() != null ? request.getOwnerId() : currentUserId;
    if (!systemAdmin && !ownerId.equals(currentUserId)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Nur Systemadministratoren können beim Erstellen einen anderen Eigentümer festlegen");
    }
    if (!ownerId.equals(currentUserId)) {
      // The organization boundary is checked even for system admins - a user from another
      // organization must not become owner of a space in this one.
      requireUserInOrganization(ownerId, currentUser.getOrganizationId());
    }

    SpaceVisibility visibility =
        request.getVisibility() != null ? request.getVisibility() : SpaceVisibility.PRIVATE;

    Space space =
        buildValidatedSpace(
            request.getName(),
            request.getDescription(),
            false,
            visibility,
            ownerId,
            currentUser.getOrganizationId());
    appendInitialMemberships(space, ownerId, request.getInitialMembers());

    Space saved = spaceRepository.save(space);
    auditEventRecorder.recordUserAction(
        saved.getOrganizationId(),
        currentUserId,
        AuditEventType.SPACE_CREATED,
        AuditObjectType.SPACE,
        saved.getId(),
        saved.getName(),
        null,
        spaceAuditPayload(saved),
        AuditOutcome.SUCCESS,
        null);
    return toSpaceResponse(saved, currentUserId);
  }

  private Map<String, Object> spaceAuditPayload(Space space) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("name", space.getName());
    payload.put("visibility", space.getVisibility().name());
    payload.put("ownerId", space.getOwnerId().toString());
    return payload;
  }

  public List<SpaceListResponse> listSpaces(UUID currentUserId, boolean systemAdmin) {
    User currentUser = requireUser(currentUserId);
    return spaceRepository.findDistinctByMembershipsUserIdWithMemberships(currentUserId).stream()
        .filter(space -> space.getOrganizationId().equals(currentUser.getOrganizationId()))
        // #543: an archived space is left out of this list unless the caller has a chat of their
        // own in it, is the space's owner, or is a system admin - otherwise, in the typical #543
        // case where the owner has no chat of their own in the space they archived, the space
        // would vanish from their own list with no way back (#613 review, finding 3: no unarchive
        // endpoint exists, so this is the only way the owner ever sees it again).
        .filter(
            space ->
                !space.isArchived()
                    || systemAdmin
                    || space.getOwnerId().equals(currentUserId)
                    || chatRepository.existsBySpaceIdAndAuthorId(space.getId(), currentUserId))
        .map(space -> toSpaceListResponse(space, currentUserId))
        .toList();
  }

  public SpaceResponse getSpace(UUID spaceId, UUID currentUserId, boolean systemAdmin) {
    Space space = loadSpace(spaceId, currentUserId);

    if (!systemAdmin && userMembership(space, currentUserId) == null) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Sie sind kein Mitglied dieses Space");
    }

    return toSpaceResponse(space, currentUserId);
  }

  public List<SpaceMemberResponse> listMembers(
      UUID spaceId, UUID currentUserId, boolean systemAdmin) {
    Space space = loadSpace(spaceId, currentUserId);
    // #144: the member list names every member of the space - who else works in "Disziplinar-
    // verfahren" or "Umstrukturierung Abteilung 3" is itself sensitive. Unlike getSpace, which only
    // checks membership, this is restricted to ADMIN, the owner (checked explicitly by
    // requireMemberListViewer - transferOwnership never changes the new owner's membership role,
    // so the owner is not always ADMIN) and system admins.
    if (!systemAdmin) {
      requireMemberListViewer(space, currentUserId);
    }

    List<UUID> userIds = space.getMemberships().stream().map(SpaceMembership::getUserId).toList();
    Map<UUID, String> displayNames = resolveDisplayNames(userIds);

    return space.getMemberships().stream()
        .map(
            m ->
                new SpaceMemberResponse(m.getUserId(), m.getRole(), m.getCreatedAt())
                    .displayName(displayNames.get(m.getUserId())))
        .toList();
  }

  @Transactional
  public SpaceMemberResponse addMember(
      UUID spaceId, UUID memberUserId, SpaceRole requestedRole, UUID currentUserId) {
    Space space = loadSpace(spaceId, currentUserId);
    requireManager(space, currentUserId);
    // #613 review, finding 2: an archived space accepts no new content, and a new member is new
    // content in the sense the specification means - see docs/features/spaces-and-assets.md#einen-
    // space-stilllegen-archivieren-statt-löschen ("keine neuen Chats, Nachrichten, Umbenennungen
    // oder Mitglieder").
    requireNotArchived(space);
    // Resolving the target user first also turns a non-existent userId into a clean 404 instead
    // of a raw foreign-key violation from the membership insert below.
    requireUserInOrganization(memberUserId, space.getOrganizationId());

    if (userMembership(space, memberUserId) != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Der Benutzer ist bereits Mitglied dieses Space");
    }

    SpaceRole roleToAssign = requestedRole == null ? SpaceRole.MEMBER : requestedRole;
    SpaceMembership membership =
        new SpaceMembership(memberUserId, roleToAssign, space.getOrganizationId());
    space.addMembership(membership);
    spaceRepository.save(space);
    auditEventRecorder.recordUserActionOnSubject(
        space.getOrganizationId(),
        currentUserId,
        AuditEventType.SPACE_MEMBER_ADDED,
        AuditObjectType.SPACE,
        space.getId(),
        space.getName(),
        AuditSubjectKind.USER,
        memberUserId,
        null,
        Map.of("role", roleToAssign.name()),
        AuditOutcome.SUCCESS,
        null);

    return new SpaceMemberResponse(
            membership.getUserId(), membership.getRole(), membership.getCreatedAt())
        .displayName(resolveDisplayName(membership.getUserId()));
  }

  @Transactional
  public SpaceMemberResponse updateMemberRole(
      UUID spaceId, UUID memberUserId, SpaceRole newRole, UUID currentUserId) {
    Space space = loadSpace(spaceId, currentUserId);
    requireManager(space, currentUserId);
    if (newRole == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role ist erforderlich");
    }

    SpaceMembership target = userMembership(space, memberUserId);
    if (target == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mitglied des Space nicht gefunden");
    }
    if (space.getOwnerId().equals(memberUserId) && newRole != SpaceRole.ADMIN) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Die Rolle des Eigentümers kann nicht geändert werden; übertragen Sie zuerst die"
              + " Verantwortung");
    }

    SpaceRole previousRole = target.getRole();
    target.setRole(newRole);
    spaceRepository.save(space);
    auditEventRecorder.recordUserActionOnSubject(
        space.getOrganizationId(),
        currentUserId,
        AuditEventType.SPACE_MEMBER_ROLE_CHANGED,
        AuditObjectType.SPACE,
        space.getId(),
        space.getName(),
        AuditSubjectKind.USER,
        memberUserId,
        Map.of("role", previousRole.name()),
        Map.of("role", newRole.name()),
        AuditOutcome.SUCCESS,
        null);
    return new SpaceMemberResponse(target.getUserId(), target.getRole(), target.getCreatedAt())
        .displayName(resolveDisplayName(target.getUserId()));
  }

  @Transactional
  public void removeMember(UUID spaceId, UUID memberUserId, UUID currentUserId) {
    Space space = loadSpace(spaceId, currentUserId);
    requireManager(space, currentUserId);

    SpaceMembership target = userMembership(space, memberUserId);
    if (target == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mitglied des Space nicht gefunden");
    }
    if (space.getOwnerId().equals(memberUserId)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Der Eigentümer kann nicht entfernt werden; übertragen Sie zuerst die Verantwortung");
    }

    space.removeMembership(target);
    spaceRepository.save(space);
    auditEventRecorder.recordUserActionOnSubject(
        space.getOrganizationId(),
        currentUserId,
        AuditEventType.SPACE_MEMBER_REMOVED,
        AuditObjectType.SPACE,
        space.getId(),
        space.getName(),
        AuditSubjectKind.USER,
        memberUserId,
        Map.of("role", target.getRole().name()),
        null,
        AuditOutcome.SUCCESS,
        null);
  }

  @Transactional
  public void transferOwnership(
      UUID spaceId, UUID newOwnerUserId, UUID currentUserId, boolean systemAdmin) {
    Space space = loadSpace(spaceId, currentUserId);
    if (!systemAdmin && !space.getOwnerId().equals(currentUserId)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Nur der Eigentümer oder ein Systemadministrator kann die Verantwortung übertragen");
    }

    SpaceMembership newOwnerMembership = userMembership(space, newOwnerUserId);
    if (newOwnerMembership == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Der ausgewählte Benutzer ist kein Mitglied dieses Space");
    }

    UUID previousOwnerId = space.getOwnerId();
    space.transferOwnershipTo(newOwnerUserId);
    spaceRepository.save(space);
    // #392 code review: ASSET_OWNER_CHANGED is in the closed list without a library-only
    // restriction, and the spec's "Eigentuemerwechsel" line sits in the "Spaces, Bibliotheken und
    // Gruppen" block, not a library-specific one - a space ownership transfer belongs under this
    // event type, not the generic SPACE_CHANGED (which would hide it from a filter on
    // event_type = ASSET_OWNER_CHANGED).
    auditEventRecorder.recordUserAction(
        space.getOrganizationId(),
        currentUserId,
        AuditEventType.ASSET_OWNER_CHANGED,
        AuditObjectType.SPACE,
        space.getId(),
        space.getName(),
        Map.of("ownerId", previousOwnerId.toString()),
        Map.of("ownerId", newOwnerUserId.toString()),
        AuditOutcome.SUCCESS,
        null);
  }

  @Transactional
  public SpaceResponse updateSpace(
      UUID spaceId, SpaceUpdateRequest request, UUID currentUserId, boolean systemAdmin) {
    Space space = loadSpace(spaceId, currentUserId);

    SpaceMembership membership = userMembership(space, currentUserId);
    boolean adminOrOwner =
        (membership != null && membership.getRole() == SpaceRole.ADMIN)
            || space.getOwnerId().equals(currentUserId);
    if (!systemAdmin && !adminOrOwner) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Nur Administratoren oder der Eigentümer können einen Space ändern");
    }

    String normalizedName = validateName(request.getName());
    validateDescription(request.getDescription());
    String previousName = space.getName();
    String previousDescription = space.getDescription();
    SpaceVisibility previousVisibility = space.getVisibility();
    space.updateDetails(normalizedName, request.getDescription(), request.getVisibility());
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
          updated.getOrganizationId(),
          currentUserId,
          AuditEventType.SPACE_CHANGED,
          AuditObjectType.SPACE,
          updated.getId(),
          updated.getName(),
          before,
          after,
          AuditOutcome.SUCCESS,
          null);
    }
    return toSpaceResponse(updated, currentUserId);
  }

  @Transactional
  public void deleteSpace(UUID spaceId, UUID currentUserId, boolean systemAdmin) {
    Space space = loadSpace(spaceId, currentUserId);

    if (space.isDefault()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Der Standard-Space kann nicht gelöscht werden");
    }

    boolean owner = space.getOwnerId().equals(currentUserId);
    if (!systemAdmin && !owner) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Nur der Eigentümer oder ein Systemadministrator kann einen Space löschen");
    }

    // #525: chats are composition, not association - docs/features/spaces-and-assets.md#chats-
    // sind-vor-fremder-löschung-geschützt says a chat "bleibt für seinen Autor und im Nachweis
    // erhalten", so deleting the space they live in must not silently destroy them.
    // fk_chats_space_organization is ON DELETE RESTRICT (migration 032, composite as of migration
    // 047) and would reject this anyway, but a raw constraint violation surfaces as an opaque 500 -
    // this check turns it into an understandable 409 instead.
    if (chatRepository.existsBySpaceId(spaceId)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Der Space enthält noch Chats und kann deshalb nicht gelöscht werden. Archivieren Sie"
              + " den Space stattdessen.");
    }

    auditEventRecorder.recordUserAction(
        space.getOrganizationId(),
        currentUserId,
        AuditEventType.SPACE_DELETED,
        AuditObjectType.SPACE,
        space.getId(),
        space.getName(),
        spaceAuditPayload(space),
        null,
        AuditOutcome.SUCCESS,
        null);
    spaceRepository.delete(space);
  }

  /**
   * Archives a space (#543, docs/features/spaces-and-assets.md#einen-space-stilllegen-archivieren-
   * statt-löschen) - the maintainer-decided way out of a space that {@code
   * fk_chats_space_organization} (ON DELETE RESTRICT, migration 032, composite as of migration 047)
   * makes permanently undeletable because it still contains a chat authored by someone other than
   * the space owner, who cannot even see - let alone delete - that chat themselves. Archiving does
   * not remove that guard or change {@link #deleteSpace}'s behaviour: a real delete remains
   * possible once every chat is actually gone. What it does instead is stop the space from
   * accepting new content ({@code ChatService#createChat}, {@code ChatService#appendTurn}, {@code
   * ChatService#updateChat} and {@link #addMember} all reject with 409) and hide it from {@link
   * #listSpaces} for members without a chat of their own in it - but never for the owner or a
   * system admin, since there is no unarchive endpoint and the typical case (#613 review, finding
   * 3) is exactly an owner with no chat of their own in the space they just archived - while every
   * chat, including ones the owner cannot see, stays fully readable for its author.
   *
   * <p>Same permission bar as {@link #deleteSpace}: owner or system admin, and the default space
   * cannot be archived either, for the same reason it cannot be deleted (#333 - it is not this
   * user's to retire). Idempotent: archiving an already archived space is a no-op that simply
   * returns its current state, not an error.
   */
  @Transactional
  public SpaceResponse archiveSpace(UUID spaceId, UUID currentUserId, boolean systemAdmin) {
    Space space = loadSpace(spaceId, currentUserId);

    if (space.isDefault()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Der Standard-Space kann nicht archiviert werden");
    }

    boolean owner = space.getOwnerId().equals(currentUserId);
    if (!systemAdmin && !owner) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Nur der Eigentümer oder ein Systemadministrator kann einen Space archivieren");
    }

    if (space.isArchived()) {
      return toSpaceResponse(space, currentUserId);
    }

    space.archive();
    Space archived = spaceRepository.save(space);
    auditEventRecorder.recordUserAction(
        archived.getOrganizationId(),
        currentUserId,
        AuditEventType.SPACE_ARCHIVED,
        AuditObjectType.SPACE,
        archived.getId(),
        archived.getName(),
        spaceAuditPayload(archived),
        null,
        AuditOutcome.SUCCESS,
        null);
    return toSpaceResponse(archived, currentUserId);
  }

  /**
   * Creates the automatic default space (and its owner {@code ADMIN} membership) for a user if it
   * does not exist yet.
   *
   * <p>Two concurrent first logins of the same user can both pass the {@code existsBy} check below
   * before either has inserted a row - the check alone cannot prevent that. The partial unique
   * index {@code uk_spaces_default_owner} (migration 015) is the actual guard, enforced through
   * {@link SpaceRepository#insertDefaultSpaceIfAbsent}'s {@code ON CONFLICT ... DO NOTHING}: at
   * most one of several concurrent calls for the same owner actually inserts a row, the rest are
   * silent no-ops - never a {@link DataIntegrityViolationException} to catch, and never a second
   * query to re-read the winner's row, because this method returns {@code void} and the caller
   * (idempotent by design - see {@code UserService#ensurePersonalSpace}) does not need it back. A
   * genuinely unrelated constraint violation (e.g. a dangling {@code ownerId}) still throws
   * normally, because {@code ON CONFLICT} only ever suppresses the one named partial index, never
   * any other constraint.
   *
   * <p><b>Replaced the earlier catch-and-reread pattern</b> (#201/#305 code review): under the
   * {@code UserServiceCreationRaceIntegrationTest} 12-concurrent-first-login load, the previous
   * insert-then-catch-{@code DataIntegrityViolationException}-then-reread sequence needed up to two
   * round trips per losing caller (the failed insert attempt, whose aborted transaction then had to
   * be rolled back before the connection could be reused, plus the follow-up read); #201 had
   * temporarily doubled the number of callers doing this in the same per-login sequence by adding a
   * sibling personal-library provisioning call right after this method - since removed again by
   * #522, which deleted the automatic personal library entirely. That doubling was enough
   * additional connection-pool queueing to intermittently exceed Hikari's default 30-second {@code
   * connectionTimeout} at the production default pool size of 10 - not a deadlock (each connection
   * was still only held by one caller at a time; see the {@code Propagation.NOT_SUPPORTED} note
   * below, which fixes a separate, real double-connection defect this method also had), just more
   * total round trips than the pool could clear in time. The single {@code INSERT ... ON CONFLICT}
   * below is one round trip regardless of whether it wins or loses the race, cutting that queueing
   * roughly in half without weakening the guarantee - confirmed by {@code
   * UserServiceCreationRaceIntegrationTest} passing repeatedly at the production default pool size
   * of 10, not a raised test-only pool size (see that test's Javadoc for why raising the pool was
   * rejected as treating the symptom).
   *
   * <p><b>Caller requirement:</b> because the insert runs on its own connection, {@code userId}
   * must already be committed and visible to other connections when this method is called - not
   * merely persisted in a still-open transaction. Calling this from inside the same transaction
   * that first creates the user row will fail with a {@code fk_spaces_owner_organization} violation
   * (composite as of migration 047), because the {@code REQUIRES_NEW} connection cannot see the
   * uncommitted row (regression fixed as a follow-up to #265/#280; see {@code
   * UserService#ensurePersonalSpaceAfterCommit}, which defers this call to a post-commit hook for
   * exactly this reason).
   *
   * <p><b>{@code Propagation.NOT_SUPPORTED}, overriding the class-level
   * {@code @Transactional(readOnly = true)}:</b> without this override, calling this public method
   * through the Spring proxy opened an ambient read-only transaction (holding one JDBC connection)
   * for this method's entire duration, while {@code requiresNewTransactionTemplate} below opened a
   * <em>second</em>, independent connection for its {@code REQUIRES_NEW} transaction - two
   * connections held by one caller at once, the same class of bug #299 fixed in {@code
   * UserService.findOrCreateUser}. {@code NOT_SUPPORTED} suspends any ambient transaction for this
   * method's duration (there normally is none, since {@code UserService.findOrCreateUser} itself is
   * not {@code @Transactional} either - see #293/#299) and leaves only the one connection {@code
   * requiresNewTransactionTemplate} actually needs.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void ensureDefaultSpace(UUID userId, UUID organizationId) {
    if (spaceRepository.existsByOwnerIdAndIsDefaultTrue(userId)) {
      return;
    }

    requiresNewTransactionTemplate.executeWithoutResult(
        status ->
            spaceRepository.insertDefaultSpaceIfAbsent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Meine Dokumente",
                "Privater persönlicher Space",
                userId,
                organizationId));
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
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name ist erforderlich");
    }
    String trimmed = name.trim();
    if (trimmed.length() > MAX_NAME_LENGTH) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "name darf höchstens " + MAX_NAME_LENGTH + " Zeichen umfassen");
    }
    return trimmed;
  }

  private void validateDescription(String description) {
    if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "description darf höchstens " + MAX_DESCRIPTION_LENGTH + " Zeichen umfassen");
    }
  }

  private User requireUser(UUID userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Benutzer nicht gefunden"));
  }

  /**
   * Resolves a user and enforces the organization boundary for it. Used for every foreign userId
   * that a request body can supply (owner, initial members, added members) - without this, a
   * request could reference a user from another organization and the resulting membership row would
   * silently violate the organization invariant. Returns 404 rather than 403 both when the user
   * does not exist and when it belongs to a different organization, so that a caller cannot
   * distinguish "no such user" from "user in another organization".
   */
  private User requireUserInOrganization(UUID userId, UUID organizationId) {
    User user = requireUser(userId);
    if (!user.getOrganizationId().equals(organizationId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Benutzer nicht gefunden");
    }
    return user;
  }

  /**
   * Loads a space and enforces the organization boundary. A space belonging to a different
   * organization than the caller is treated as not found - the boundary is not overstepped even to
   * reveal existence, and this applies to system administrators as well.
   */
  private Space loadSpace(UUID spaceId, UUID currentUserId) {
    User currentUser = requireUser(currentUserId);
    Space space =
        spaceRepository
            .findByIdWithMemberships(spaceId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Space nicht gefunden"));

    if (!space.getOrganizationId().equals(currentUser.getOrganizationId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Space nicht gefunden");
    }
    return space;
  }

  private SpaceMembership requireMembership(Space space, UUID userId) {
    SpaceMembership membership = userMembership(space, userId);
    if (membership == null) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Sie sind kein Mitglied dieses Space");
    }
    return membership;
  }

  private SpaceMembership requireManager(Space space, UUID userId) {
    SpaceMembership membership = requireMembership(space, userId);
    if (membership.getRole() != SpaceRole.ADMIN) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Nur Administratoren können Mitglieder verwalten");
    }
    return membership;
  }

  /**
   * #144: the member list is restricted to ADMIN, the owner and system admins. The owner check is
   * explicit and not folded into "owner's membership is always ADMIN" - {@link #transferOwnership}
   * only reassigns {@code Space.ownerId} and never touches the new owner's {@link SpaceMembership}
   * role (review finding on #674), so a space can genuinely have an owner whose own membership is
   * MEMBER or CURATOR.
   */
  private SpaceMembership requireMemberListViewer(Space space, UUID userId) {
    SpaceMembership membership = requireMembership(space, userId);
    if (membership.getRole() != SpaceRole.ADMIN && !space.getOwnerId().equals(userId)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Nur Administratoren oder der Eigentümer können die Mitgliederliste einsehen");
    }
    return membership;
  }

  /**
   * #613 review, finding 2: "kein neuer Inhalt" is not only "no new chats" (already enforced by
   * {@code ChatService#createChat}) - it also covers adding a new member, which is why this is
   * called from {@link #addMember} too. See docs/features/spaces-and-assets.md#einen-space-
   * stilllegen-archivieren-statt-löschen.
   */
  private void requireNotArchived(Space space) {
    if (space.isArchived()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Der Space ist archiviert und lässt keine neuen Mitglieder mehr zu");
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
      Space space, UUID ownerId, List<SpaceMemberRequest> initialMembers) {
    Map<UUID, SpaceRole> resolvedRoles = new LinkedHashMap<>();
    if (initialMembers != null) {
      for (SpaceMemberRequest member : initialMembers) {
        if (member == null) {
          continue;
        }
        resolvedRoles.put(member.getUserId(), member.getRole());
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
          space.addMembership(new SpaceMembership(userId, role, space.getOrganizationId()));
        });
  }

  private SpaceMembership userMembership(Space space, UUID userId) {
    return space.getMemberships().stream()
        .filter(membership -> membership.getUserId().equals(userId))
        .findFirst()
        .orElse(null);
  }

  private SpaceListResponse toSpaceListResponse(Space space, UUID currentUserId) {
    SpaceMembership membership = userMembership(space, currentUserId);
    return new SpaceListResponse(
            space.getId(),
            space.getName(),
            space.isDefault(),
            space.isArchived(),
            space.getMemberships().size(),
            space.getCreatedAt(),
            space.getUpdatedAt())
        .description(space.getDescription())
        .visibility(space.getVisibility())
        .userRole(membership == null ? null : membership.getRole());
  }

  private SpaceResponse toSpaceResponse(Space space, UUID currentUserId) {
    SpaceMembership membership = userMembership(space, currentUserId);
    Map<String, Long> roleCounts = new HashMap<>();
    for (SpaceRole role : SpaceRole.values()) {
      roleCounts.put(role.name(), 0L);
    }
    space.getMemberships().forEach(m -> roleCounts.merge(m.getRole().name(), 1L, Long::sum));

    // #144: the aggregated roleCounts stay visible to every member ("how big is this room"), but
    // the full member list with identities and display names is not part of SpaceResponse anymore
    // - it is only available via listMembers, restricted to ADMIN, owner and system admins.
    return new SpaceResponse(
            space.getId(),
            space.getName(),
            space.isDefault(),
            space.isArchived(),
            space.getOwnerId(),
            space.getMemberships().size(),
            roleCounts,
            space.getCreatedAt(),
            space.getUpdatedAt())
        .description(space.getDescription())
        .visibility(space.getVisibility())
        .userRole(membership == null ? null : membership.getRole());
  }
}
