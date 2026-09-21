package io.opaa.library;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.LibraryOwnerType;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.permission.AssetOwnershipDirectory;
import io.opaa.permission.AssetOwnershipHistoryService;
import io.opaa.permission.AssetType;
import io.opaa.permission.PermissionSubject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
  private final AssetOwnershipHistoryService ownershipHistory;
  private final AuditEventRecorder auditEventRecorder;

  LibraryAssetOwnershipDirectory(
      KnowledgeLibraryRepository libraryRepository,
      AssetOwnershipHistoryService ownershipHistory,
      AuditEventRecorder auditEventRecorder) {
    this.libraryRepository = libraryRepository;
    this.ownershipHistory = ownershipHistory;
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

  @Override
  public void transferOwnership(
      UUID assetId, PermissionSubject newOwner, UUID actorUserId, UUID transferId, Instant at) {
    KnowledgeLibrary library = libraryRepository.findById(assetId).orElseThrow();
    UUID previousOwnerId = library.getOwnerId();
    library.transferOwnershipTo(ownerTypeOf(newOwner), newOwner.id());
    libraryRepository.save(library);
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

  private static LibraryOwnerType ownerTypeOf(PermissionSubject owner) {
    return owner.type() == PermissionSubjectType.GROUP
        ? LibraryOwnerType.GROUP
        : LibraryOwnerType.USER;
  }
}
