package io.opaa.library;

import io.opaa.api.dto.AssetGrantRequest;
import io.opaa.api.dto.AssetGrantResponse;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.Group;
import io.opaa.group.GroupRepository;
import io.opaa.group.PermissionSubjectType;
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
        grant.updateRole(request.getRole(), request.getExpiresAt());
      }
    } else {
      requireGroupInOrganization(request.getSubjectId(), library.getOrganizationId());
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

    grantRepository.delete(grant);
    invalidateAfterCommit(library.getId());
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

  private void requireGroupInOrganization(UUID groupId, UUID organizationId) {
    Group group =
        groupRepository
            .findById(groupId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Gruppe nicht gefunden"));
    if (!group.getOrganizationId().equals(organizationId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Gruppe nicht gefunden");
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
