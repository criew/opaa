package io.opaa.library;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.LibraryOwnerType;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.permission.AssetGrant;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.permission.AssetOwnershipDirectory;
import io.opaa.permission.AssetOwnershipHistoryService;
import io.opaa.permission.AssetType;
import io.opaa.permission.PermissionHistoryService;
import io.opaa.permission.PermissionSubject;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The knowledge-library answer to {@link AssetOwnershipDirectory}: {@code
 * fk_knowledge_libraries_owner_group_organization} is RESTRICT, so a group that still owns a
 * library must be refused before the delete reaches the database - and a transfer is what makes
 * that group deletable. The one place {@code io.opaa.group} and {@code io.opaa.permission} learn
 * that libraries exist at all - through the port, not through this package.
 */
@Component
class LibraryAssetOwnershipDirectory implements AssetOwnershipDirectory {

  private final KnowledgeLibraryRepository libraryRepository;
  private final AssetGrantRepository grantRepository;
  private final AssetOwnershipHistoryService ownershipHistory;
  private final PermissionHistoryService permissionHistory;
  private final AuditEventRecorder auditEventRecorder;

  LibraryAssetOwnershipDirectory(
      KnowledgeLibraryRepository libraryRepository,
      AssetGrantRepository grantRepository,
      AssetOwnershipHistoryService ownershipHistory,
      PermissionHistoryService permissionHistory,
      AuditEventRecorder auditEventRecorder) {
    this.libraryRepository = libraryRepository;
    this.grantRepository = grantRepository;
    this.ownershipHistory = ownershipHistory;
    this.permissionHistory = permissionHistory;
    this.auditEventRecorder = auditEventRecorder;
  }

  @Override
  public AssetType assetType() {
    return KnowledgeLibrary.ASSET_TYPE;
  }

  @Override
  public boolean existsAssetOwnedByGroup(UUID groupId) {
    return libraryRepository.existsByOwnerGroupId(groupId);
  }

  @Override
  public String ownedAssetConflictMessage() {
    return "Die Gruppe besitzt noch Bibliotheken und kann nicht gelöscht werden";
  }

  @Override
  public long countAssetsOwnedBy(PermissionSubject owner) {
    return owner.type() == PermissionSubjectType.GROUP
        ? libraryRepository.countByOwnerGroupIdAndOrganizationId(owner.id(), owner.organizationId())
        : libraryRepository.countByOwnerUserIdAndOrganizationId(owner.id(), owner.organizationId());
  }

  @Override
  public Map<UUID, Long> countAssetsOwnedByGroups(Collection<UUID> groupIds, UUID organizationId) {
    if (groupIds.isEmpty()) {
      return Map.of();
    }
    return libraryRepository.countByOwnerGroupIdIn(groupIds, organizationId).stream()
        .collect(
            Collectors.toMap(
                KnowledgeLibraryRepository.OwnerGroupCount::getOwnerGroupId,
                KnowledgeLibraryRepository.OwnerGroupCount::getLibraryCount));
  }

  @Override
  public List<UUID> assetIdsOwnedBy(PermissionSubject owner) {
    List<KnowledgeLibrary> owned =
        owner.type() == PermissionSubjectType.GROUP
            ? libraryRepository.findByOwnerGroupId(owner.id())
            : libraryRepository.findByOwnerUserId(owner.id());
    return owned.stream()
        .filter(library -> library.getOrganizationId().equals(owner.organizationId()))
        .map(KnowledgeLibrary::getId)
        .toList();
  }

  /**
   * Moves the owner column <b>and the grant that owner column depends on</b>. A role on a library
   * comes from grant rows alone ({@code AssetAccessService#bestRole} knows no owner column), so
   * without the second half the successor would hold nothing on their own library and the departed
   * owner would keep everything - the exact opposite of "danach hält die Quelle nichts mehr und das
   * Ziel alles". Which role accompanies ownership mirrors {@code
   * KnowledgeLibraryService#createLibrary}: {@code OWNER} for a person, {@code MANAGER} for a
   * group.
   */
  @Override
  public void transferOwnership(
      UUID assetId, PermissionSubject newOwner, UUID actorUserId, UUID transferId, Instant at) {
    KnowledgeLibrary library = libraryRepository.findById(assetId).orElseThrow();
    PermissionSubject previousOwner = ownerSubjectOf(library);
    UUID previousOwnerId = library.getOwnerId();
    library.transferOwnershipTo(ownerTypeOf(newOwner), newOwner.id());
    libraryRepository.save(library);
    moveOwnerGrant(library, previousOwner, newOwner, actorUserId, transferId, at);
    ownershipHistory.recordTransferred(
        KnowledgeLibrary.ASSET_TYPE, library.getId(), newOwner, actorUserId, transferId, at);
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(library.getOrganizationId())
            .actor(actorUserId)
            .type(AuditEventType.ASSET_OWNER_CHANGED)
            .object(AuditObjectType.KNOWLEDGE_LIBRARY, library.getId(), library.getName())
            .before(Map.of("ownerId", previousOwnerId.toString()))
            .after(Map.of("ownerId", newOwner.id().toString(), "transferId", transferId.toString()))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  /**
   * Gives the new owner the role that goes with ownership and ends the previous owner's, both at
   * the transfer's one boundary and under its one id. Raising only: a target that already holds a
   * stronger role keeps it, and a target that already holds exactly this role gets no second
   * interval - its state did not change. Idempotent next to the grant part of the same transfer,
   * which may already have moved this very row.
   */
  private void moveOwnerGrant(
      KnowledgeLibrary library,
      PermissionSubject previousOwner,
      PermissionSubject newOwner,
      UUID actorUserId,
      UUID transferId,
      Instant at) {
    AssetRole ownerRole =
        newOwner.type() == PermissionSubjectType.GROUP ? AssetRole.MANAGER : AssetRole.OWNER;
    AssetGrant existing = findGrant(library.getId(), newOwner);
    if (existing == null) {
      AssetGrant granted =
          grantRepository.save(
              newOwner.type() == PermissionSubjectType.GROUP
                  ? AssetGrant.forGroup(
                      KnowledgeLibrary.ASSET_TYPE,
                      library.getId(),
                      library.getOrganizationId(),
                      newOwner.id(),
                      ownerRole,
                      null,
                      actorUserId)
                  : AssetGrant.forUser(
                      KnowledgeLibrary.ASSET_TYPE,
                      library.getId(),
                      library.getOrganizationId(),
                      newOwner.id(),
                      ownerRole,
                      null,
                      actorUserId));
      permissionHistory.recordGrantTransferredIn(granted, actorUserId, transferId, at);
    } else if (existing.getRole().ordinal() < ownerRole.ordinal() || existing.isExpired(at)) {
      existing.updateRole(ownerRole, null, actorUserId, at);
      grantRepository.save(existing);
      permissionHistory.recordGrantTransferredIn(existing, actorUserId, transferId, at);
    }

    AssetGrant left = findGrant(library.getId(), previousOwner);
    if (left != null) {
      permissionHistory.recordGrantTransferredOut(left, actorUserId, transferId, at);
      grantRepository.delete(left);
    }
  }

  private AssetGrant findGrant(UUID libraryId, PermissionSubject subject) {
    return (subject.type() == PermissionSubjectType.GROUP
            ? grantRepository.findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectGroupId(
                KnowledgeLibrary.ASSET_TYPE, libraryId, PermissionSubjectType.GROUP, subject.id())
            : grantRepository.findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectUserId(
                KnowledgeLibrary.ASSET_TYPE, libraryId, PermissionSubjectType.USER, subject.id()))
        .orElse(null);
  }

  private static PermissionSubject ownerSubjectOf(KnowledgeLibrary library) {
    return library.getOwnerType() == LibraryOwnerType.GROUP
        ? PermissionSubject.group(library.getOwnerId(), library.getOrganizationId())
        : PermissionSubject.user(library.getOwnerId(), library.getOrganizationId());
  }

  private static LibraryOwnerType ownerTypeOf(PermissionSubject owner) {
    return owner.type() == PermissionSubjectType.GROUP
        ? LibraryOwnerType.GROUP
        : LibraryOwnerType.USER;
  }
}
