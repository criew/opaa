package io.opaa.asset;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditOutcome;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The asset shell's answer to {@link AssetOwnershipDirectory}, for every asset type at once: the
 * owner foreign keys of {@code assets} are RESTRICT, so a group that still owns an asset must be
 * refused before the delete reaches the database - and a transfer is what makes it deletable. A
 * transfer is the one way an asset changes its owner, so the owner change lives here: the owner
 * columns, the grant that goes with ownership, the ownership interval and the audit entry. It
 * depends on nothing that asks the succession guard - the succession sources reach this directory.
 */
@Component
class AssetShellOwnershipDirectory implements AssetOwnershipDirectory {

  private final AssetRepository assetRepository;
  private final AssetTypes assetTypes;
  private final AssetGrantRepository grantRepository;
  private final AssetOwnershipHistoryService ownershipHistory;
  private final PermissionHistoryService permissionHistory;
  private final AuditEventRecorder auditEventRecorder;

  AssetShellOwnershipDirectory(
      AssetRepository assetRepository,
      AssetTypes assetTypes,
      AssetGrantRepository grantRepository,
      AssetOwnershipHistoryService ownershipHistory,
      PermissionHistoryService permissionHistory,
      AuditEventRecorder auditEventRecorder) {
    this.assetRepository = assetRepository;
    this.assetTypes = assetTypes;
    this.grantRepository = grantRepository;
    this.ownershipHistory = ownershipHistory;
    this.permissionHistory = permissionHistory;
    this.auditEventRecorder = auditEventRecorder;
  }

  @Override
  public boolean answersFor(AssetType assetType) {
    return assetTypes.find(assetType).isPresent();
  }

  @Override
  public boolean existsAssetOwnedByGroup(UUID groupId) {
    return assetRepository.existsByOwnerGroupId(groupId);
  }

  /** Names the holdings actually in the way - "Bibliotheken" while only libraries exist. */
  @Override
  public String ownedAssetConflictMessage(UUID groupId) {
    String holdings =
        assetRepository.findTypesOwnedByGroup(groupId).stream()
            .map(
                type ->
                    assetTypes
                        .find(AssetType.of(type))
                        .map(AssetTypeDefinition::plural)
                        .orElse("Objekte"))
            .distinct()
            .sorted()
            .collect(Collectors.joining(" und "));
    return "Die Gruppe besitzt noch "
        + (holdings.isEmpty() ? "Objekte" : holdings)
        + " und kann nicht gelöscht werden";
  }

  @Override
  public long countAssetsOwnedBy(PermissionSubject owner) {
    return owner.type() == PermissionSubjectType.GROUP
        ? assetRepository.countByOwnerGroupIdAndOrganizationId(owner.id(), owner.organizationId())
        : assetRepository.countByOwnerUserIdAndOrganizationId(owner.id(), owner.organizationId());
  }

  @Override
  public Map<UUID, Long> countAssetsOwnedByGroups(Collection<UUID> groupIds, UUID organizationId) {
    if (groupIds.isEmpty()) {
      return Map.of();
    }
    return assetRepository.countByOwnerGroupIdIn(groupIds, organizationId).stream()
        .collect(
            Collectors.toMap(
                AssetRepository.OwnerGroupCount::getOwnerGroupId,
                AssetRepository.OwnerGroupCount::getAssetCount));
  }

  @Override
  public Map<AssetType, List<UUID>> assetIdsOwnedBy(PermissionSubject owner) {
    List<Asset> owned =
        owner.type() == PermissionSubjectType.GROUP
            ? assetRepository.findByOwnerGroupIdAndOrganizationId(
                owner.id(), owner.organizationId())
            : assetRepository.findByOwnerUserIdAndOrganizationId(
                owner.id(), owner.organizationId());
    Map<AssetType, List<UUID>> byType = new LinkedHashMap<>();
    for (Asset asset : owned) {
      byType
          .computeIfAbsent(asset.getAssetType(), type -> new java.util.ArrayList<>())
          .add(asset.getId());
    }
    return byType;
  }

  @Override
  public void transferOwnership(
      AssetType assetType,
      UUID assetId,
      PermissionSubject newOwner,
      UUID actorUserId,
      UUID transferId,
      Instant at) {
    Asset asset =
        assetRepository
            .findById(assetId)
            .filter(found -> found.getAssetType().equals(assetType))
            .orElseThrow();
    transferOwnership(asset, newOwner, actorUserId, transferId, at);
    assetRepository.save(asset);
  }

  /**
   * Hands the asset to {@code newOwner} as part of a transfer (#1834, ADR-0036 Entscheidung 10):
   * the owner columns, the grant ownership goes with, the ownership interval and the audit entry. A
   * role comes from grant rows alone, so without the grant half the successor would hold nothing
   * and the previous owner everything.
   */
  private void transferOwnership(
      Asset asset, PermissionSubject newOwner, UUID actorUserId, UUID transferId, Instant at) {
    AssetTypeDefinition definition = assetTypes.require(asset.getAssetType());
    PermissionSubject previousOwner = asset.ownerSubject();
    UUID previousOwnerId = asset.getOwnerId();
    asset.applyOwner(
        newOwner.type() == PermissionSubjectType.GROUP ? AssetOwnerType.GROUP : AssetOwnerType.USER,
        newOwner.id());
    moveOwnerGrant(asset, previousOwner, newOwner, actorUserId, transferId, at);
    ownershipHistory.recordTransferred(
        asset.getAssetType(), asset.getId(), newOwner, actorUserId, transferId, at);
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(asset.getOrganizationId())
            .actor(actorUserId)
            .type(AuditEventType.ASSET_OWNER_CHANGED)
            .object(definition.auditObjectType(), asset.getId(), asset.getName())
            .before(Map.of("ownerId", previousOwnerId.toString()))
            .after(Map.of("ownerId", newOwner.id().toString(), "transferId", transferId.toString()))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  /**
   * Gives the new owner the role that goes with ownership and ends the previous owner's, both at
   * the transfer's one boundary and under its one id. Raising only: a target that already holds a
   * stronger role keeps it, and one that already holds exactly this role gets no second interval.
   * Idempotent next to the grant part of the same transfer, which may already have moved this row.
   */
  private void moveOwnerGrant(
      Asset asset,
      PermissionSubject previousOwner,
      PermissionSubject newOwner,
      UUID actorUserId,
      UUID transferId,
      Instant at) {
    AssetRole ownerRole =
        newOwner.type() == PermissionSubjectType.GROUP ? AssetRole.MANAGER : AssetRole.OWNER;
    AssetGrant existing = findGrant(asset, newOwner);
    if (existing == null) {
      AssetGrant granted =
          grantRepository.save(
              newOwner.type() == PermissionSubjectType.GROUP
                  ? AssetGrant.forGroup(
                      asset.getAssetType(),
                      asset.getId(),
                      asset.getOrganizationId(),
                      newOwner.id(),
                      ownerRole,
                      null,
                      actorUserId,
                      null)
                  : AssetGrant.forUser(
                      asset.getAssetType(),
                      asset.getId(),
                      asset.getOrganizationId(),
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

    AssetGrant left = findGrant(asset, previousOwner);
    if (left != null) {
      permissionHistory.recordGrantTransferredOut(left, actorUserId, transferId, at);
      grantRepository.delete(left);
    }
  }

  private AssetGrant findGrant(Asset asset, PermissionSubject subject) {
    return (subject.type() == PermissionSubjectType.GROUP
            ? grantRepository.findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectGroupId(
                asset.getAssetType(), asset.getId(), PermissionSubjectType.GROUP, subject.id())
            : grantRepository.findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectUserId(
                asset.getAssetType(), asset.getId(), PermissionSubjectType.USER, subject.id()))
        .orElse(null);
  }
}
