package io.opaa.space;

import io.opaa.api.dto.SpaceListResponse;
import io.opaa.api.dto.SpaceMemberRequest;
import io.opaa.api.dto.SpaceMemberResponse;
import io.opaa.api.dto.SpaceRequest;
import io.opaa.api.dto.SpaceResponse;
import io.opaa.api.dto.SpaceUpdateRequest;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  private final TransactionTemplate requiresNewTransactionTemplate;

  public SpaceService(
      SpaceRepository spaceRepository,
      UserRepository userRepository,
      PlatformTransactionManager transactionManager) {
    this.spaceRepository = spaceRepository;
    this.userRepository = userRepository;
    this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
    this.requiresNewTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Transactional
  public SpaceResponse createSpace(SpaceRequest request, UUID currentUserId, boolean systemAdmin) {
    User currentUser = requireUser(currentUserId);
    SpaceKind kind = request.getKind();

    if (kind == SpaceKind.PERSONAL) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Persönliche Spaces werden automatisch angelegt");
    }
    if (kind == SpaceKind.TEAM && !systemAdmin) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Nur Systemadministratoren können Team-Spaces erstellen");
    }

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
            kind,
            visibility,
            ownerId,
            currentUser.getOrganizationId());
    appendInitialMemberships(space, ownerId, request.getInitialMembers());

    Space saved = spaceRepository.save(space);
    return toSpaceResponse(saved, currentUserId);
  }

  public List<SpaceListResponse> listSpaces(UUID currentUserId) {
    User currentUser = requireUser(currentUserId);
    return spaceRepository.findDistinctByMembershipsUserIdWithMemberships(currentUserId).stream()
        .filter(space -> space.getOrganizationId().equals(currentUser.getOrganizationId()))
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
    if (!systemAdmin) {
      requireMembership(space, currentUserId);
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
    rejectPersonalSpaceMemberChanges(space);
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

    target.setRole(newRole);
    spaceRepository.save(space);
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

    space.transferOwnershipTo(newOwnerUserId);
    spaceRepository.save(space);
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
    space.updateDetails(normalizedName, request.getDescription(), request.getVisibility());
    Space updated = spaceRepository.save(space);
    return toSpaceResponse(updated, currentUserId);
  }

  @Transactional
  public void deleteSpace(UUID spaceId, UUID currentUserId, boolean systemAdmin) {
    Space space = loadSpace(spaceId, currentUserId);

    if (space.isPersonal()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Persönliche Spaces können nicht gelöscht werden");
    }

    boolean owner = space.getOwnerId().equals(currentUserId);
    if (!systemAdmin && !owner) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Nur der Eigentümer oder ein Systemadministrator kann einen Space löschen");
    }

    spaceRepository.delete(space);
  }

  /**
   * Creates the automatic personal space (and its owner {@code ADMIN} membership) for a user if it
   * does not exist yet.
   *
   * <p>Two concurrent first logins of the same user can both pass the {@code existsBy} check below
   * before either has inserted a row - the check alone cannot prevent that. The partial unique
   * index {@code uk_spaces_personal_owner} (migration 010) is the actual guard, enforced through
   * {@link SpaceRepository#insertPersonalSpaceIfAbsent}'s {@code ON CONFLICT ... DO NOTHING}: at
   * most one of several concurrent calls for the same owner actually inserts a row, the rest are
   * silent no-ops - never a {@link DataIntegrityViolationException} to catch, and never a second
   * query to re-read the winner's row, because this method returns {@code void} and the caller
   * (idempotent by design - see {@code UserService#ensureBothPersonalAssets}) does not need it
   * back. A genuinely unrelated constraint violation (e.g. a dangling {@code ownerId}) still throws
   * normally, because {@code ON CONFLICT} only ever suppresses the one named partial index, never
   * any other constraint.
   *
   * <p><b>Replaced the earlier catch-and-reread pattern</b> (#201/#305 code review): under the
   * {@code UserServiceCreationRaceIntegrationTest} 12-concurrent-first-login load, the previous
   * insert-then-catch-{@code DataIntegrityViolationException}-then-reread sequence needed up to two
   * round trips per losing caller (the failed insert attempt, whose aborted transaction then had to
   * be rolled back before the connection could be reused, plus the follow-up read), and #201
   * doubled the number of callers doing this in the same per-login sequence by adding {@code
   * KnowledgeLibraryService#ensurePersonalLibrary} right after this method. That was enough
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
   * that first creates the user row will fail with a {@code fk_spaces_owner} violation, because the
   * {@code REQUIRES_NEW} connection cannot see the uncommitted row (regression fixed as a follow-up
   * to #265/#280; see {@code UserService#ensurePersonalAssetsAfterCommit}, which defers this call
   * to a post-commit hook for exactly this reason).
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
  public void ensurePersonalSpace(UUID userId, UUID organizationId) {
    if (spaceRepository.existsByOwnerIdAndKind(userId, SpaceKind.PERSONAL)) {
      return;
    }

    requiresNewTransactionTemplate.executeWithoutResult(
        status ->
            spaceRepository.insertPersonalSpaceIfAbsent(
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
      SpaceKind kind,
      SpaceVisibility visibility,
      UUID ownerId,
      UUID organizationId) {
    String normalizedName = validateName(name);
    validateDescription(description);
    return new Space(normalizedName, description, kind, visibility, ownerId, organizationId);
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

  private void rejectPersonalSpaceMemberChanges(Space space) {
    if (space.isPersonal()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Zu persönlichen Spaces können keine Mitglieder hinzugefügt werden");
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
            space.getKind(),
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

    List<UUID> memberIds = space.getMemberships().stream().map(SpaceMembership::getUserId).toList();
    Map<UUID, String> displayNames = resolveDisplayNames(memberIds);

    List<SpaceMemberResponse> members =
        space.getMemberships().stream()
            .map(
                m ->
                    new SpaceMemberResponse(m.getUserId(), m.getRole(), m.getCreatedAt())
                        .displayName(displayNames.get(m.getUserId())))
            .toList();

    return new SpaceResponse(
            space.getId(),
            space.getName(),
            space.getKind(),
            space.getOwnerId(),
            members.size(),
            roleCounts,
            members,
            space.getCreatedAt(),
            space.getUpdatedAt())
        .description(space.getDescription())
        .visibility(space.getVisibility())
        .userRole(membership == null ? null : membership.getRole());
  }
}
