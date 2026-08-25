package io.opaa.space;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SpaceVisibility;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
      PlatformTransactionManager transactionManager) {
    this.spaceRepository = spaceRepository;
    this.chatRepository = chatRepository;
    this.userRepository = userRepository;
    this.auditEventRecorder = auditEventRecorder;
    this.associationService = associationService;
    this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
    this.requiresNewTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    // Same bound as GroupMembershipResolver's per-user cache; unlike that one this needs no
    // expireAfterWrite, see the field Javadoc above.
    this.personalSpaceProvisioned = Caffeine.newBuilder().maximumSize(50_000).build();
  }

  @Transactional
  public Space createSpace(SpaceCreation creation, CurrentUser caller) {
    // #333 removed SpaceKind: every user may create any number of spaces, including ones they work
    // in alone. Only the default space is special, and it is created automatically rather than
    // through this endpoint - see ensureDefaultSpace.
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
    List<Space> memberSpaces =
        spaceRepository.findDistinctByMembershipsUserIdWithMemberships(caller.id()).stream()
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
                    chatCounts.getOrDefault(space.getId(), 0L).intValue()))
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

  public Space getSpace(UUID spaceId, CurrentUser caller) {
    Space space = loadSpace(spaceId, caller);

    SpaceAccessPolicy.requireMember(space, caller);

    return space;
  }

  public List<SpaceMemberView> listMembers(UUID spaceId, CurrentUser caller) {
    Space space = loadSpace(spaceId, caller);
    // #144: the member list names every member of the space - who else works in "Disziplinar-
    // verfahren" or "Umstrukturierung Abteilung 3" is itself sensitive. Unlike getSpace, which only
    // checks membership, this is restricted to ADMIN, the owner and system admins - see
    // SpaceAccessPolicy#requireMemberListViewer.
    if (!caller.isSystemAdmin()) {
      SpaceAccessPolicy.requireMemberListViewer(space, caller);
    }

    List<UUID> userIds = space.getMemberships().stream().map(SpaceMembership::getUserId).toList();
    Map<UUID, String> displayNames = resolveDisplayNames(userIds);

    return space.getMemberships().stream()
        .map(m -> new SpaceMemberView(m, displayNames.get(m.getUserId())))
        .toList();
  }

  @Transactional
  public SpaceMemberView addMember(
      UUID spaceId, UUID memberUserId, SpaceRole requestedRole, CurrentUser caller) {
    Space space = loadSpace(spaceId, caller);
    SpaceAccessPolicy.requireManager(space, caller);
    // #613 review, finding 2: an archived space accepts no new content, and a new member is new
    // content in the sense the specification means - see docs/features/spaces-and-assets.md#einen-
    // space-stilllegen-archivieren-statt-löschen ("keine neuen Chats, Nachrichten, Umbenennungen
    // oder Mitglieder").
    requireNotArchived(space);
    // Resolving the target user first also turns a non-existent userId into a clean 404 instead
    // of a raw foreign-key violation from the membership insert below.
    requireUserInOrganization(memberUserId, space.getOrganizationId());

    if (userMembership(space, memberUserId) != null) {
      throw new ConflictException("Der Benutzer ist bereits Mitglied dieses Space");
    }

    SpaceRole roleToAssign = requestedRole == null ? SpaceRole.MEMBER : requestedRole;
    SpaceMembership membership =
        new SpaceMembership(memberUserId, roleToAssign, space.getOrganizationId());
    space.addMembership(membership);
    spaceRepository.save(space);
    auditEventRecorder.recordUserActionOnSubject(
        AuditEvent.builder()
            .organizationId(space.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.SPACE_MEMBER_ADDED)
            .object(AuditObjectType.SPACE, space.getId(), space.getName())
            .subject(AuditSubjectKind.USER, memberUserId)
            .after(Map.of("role", roleToAssign.name()))
            .outcome(AuditOutcome.SUCCESS)
            .build());

    return new SpaceMemberView(membership, resolveDisplayName(membership.getUserId()));
  }

  @Transactional
  public SpaceMemberView updateMemberRole(
      UUID spaceId, UUID memberUserId, SpaceRole newRole, CurrentUser caller) {
    Space space = loadSpace(spaceId, caller);
    SpaceAccessPolicy.requireManager(space, caller);
    if (newRole == null) {
      throw new ValidationException("role ist erforderlich");
    }

    SpaceMembership target = userMembership(space, memberUserId);
    if (target == null) {
      throw new NotFoundException("Mitglied des Space nicht gefunden");
    }
    if (space.getOwnerId().equals(memberUserId) && newRole != SpaceRole.ADMIN) {
      throw new ValidationException(
          "Die Rolle des Eigentümers kann nicht geändert werden; übertragen Sie zuerst die"
              + " Verantwortung");
    }

    SpaceRole previousRole = target.getRole();
    target.setRole(newRole);
    spaceRepository.save(space);
    auditEventRecorder.recordUserActionOnSubject(
        AuditEvent.builder()
            .organizationId(space.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.SPACE_MEMBER_ROLE_CHANGED)
            .object(AuditObjectType.SPACE, space.getId(), space.getName())
            .subject(AuditSubjectKind.USER, memberUserId)
            .before(Map.of("role", previousRole.name()))
            .after(Map.of("role", newRole.name()))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return new SpaceMemberView(target, resolveDisplayName(target.getUserId()));
  }

  @Transactional
  public void removeMember(UUID spaceId, UUID memberUserId, CurrentUser caller) {
    Space space = loadSpace(spaceId, caller);
    SpaceAccessPolicy.requireManager(space, caller);

    SpaceMembership target = userMembership(space, memberUserId);
    if (target == null) {
      throw new NotFoundException("Mitglied des Space nicht gefunden");
    }
    if (space.getOwnerId().equals(memberUserId)) {
      throw new ValidationException(
          "Der Eigentümer kann nicht entfernt werden; übertragen Sie zuerst die Verantwortung");
    }

    space.removeMembership(target);
    spaceRepository.save(space);
    auditEventRecorder.recordUserActionOnSubject(
        AuditEvent.builder()
            .organizationId(space.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.SPACE_MEMBER_REMOVED)
            .object(AuditObjectType.SPACE, space.getId(), space.getName())
            .subject(AuditSubjectKind.USER, memberUserId)
            .before(Map.of("role", target.getRole().name()))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  @Transactional
  public void transferOwnership(UUID spaceId, UUID newOwnerUserId, CurrentUser caller) {
    Space space = loadSpace(spaceId, caller);
    if (!caller.isSystemAdmin() && !space.getOwnerId().equals(caller.id())) {
      throw new AccessDeniedException(
          "Nur der Eigentümer oder ein Systemadministrator kann die Verantwortung übertragen");
    }

    SpaceMembership newOwnerMembership = userMembership(space, newOwnerUserId);
    if (newOwnerMembership == null) {
      throw new NotFoundException("Der ausgewählte Benutzer ist kein Mitglied dieses Space");
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

    SpaceMembership membership = userMembership(space, caller.id());
    boolean adminOrOwner =
        (membership != null && membership.getRole() == SpaceRole.ADMIN)
            || space.getOwnerId().equals(caller.id());
    if (!caller.isSystemAdmin() && !adminOrOwner) {
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
   * violation, because the {@code REQUIRES_NEW} connection cannot see the uncommitted row (see
   * {@code UserService#ensurePersonalSpaceAfterCommit}, which defers this call to a post-commit
   * hook for exactly this reason).
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
   * already knows to be brand new - {@code UserService.findOrCreateUser} calls this only for a
   * subject/issuer pair its own insert (not a concurrent winner's) just created. A user row that
   * did not exist a moment ago cannot already own a personal space, so the {@code existsBy} check
   * {@link #ensureDefaultSpace(UUID, UUID)} performs first is guaranteed to return {@code false}
   * here - calling it anyway would spend a whole extra pooled connection confirming a fact already
   * known. Skipping it halves this method's connection consumption to one {@code REQUIRES_NEW}
   * insert instead of an exists check plus an insert.
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
          space.addMembership(new SpaceMembership(userId, role, space.getOrganizationId()));
        });
  }

  private SpaceMembership userMembership(Space space, UUID userId) {
    return space.getMemberships().stream()
        .filter(membership -> membership.getUserId().equals(userId))
        .findFirst()
        .orElse(null);
  }
}
