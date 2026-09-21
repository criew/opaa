package io.opaa.space;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.api.types.SpaceRole;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.common.ValidationException;
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
 * The space answer to {@link AssetOwnershipDirectory} - the port through which a succession hands
 * somebody's spaces to their successor (#1834) without {@code io.opaa.permission} knowing this
 * package.
 *
 * <p><b>A space owner is always a natural person</b> (ADR-0036, Entscheidung 6), so a group neither
 * owns a space nor becomes one's owner: {@link #existsAssetOwnedByGroup} answers false by
 * construction, and a group as the new owner is refused. The personal space is excluded - nobody
 * decides its ownership, and it is not deletable either.
 */
@Component
class SpaceAssetOwnershipDirectory implements AssetOwnershipDirectory {

  private final SpaceRepository spaceRepository;
  private final SpaceMembershipRepository membershipRepository;
  private final SpaceMembershipHistoryService membershipHistory;
  private final AssetOwnershipHistoryService ownershipHistory;
  private final AuditEventRecorder auditEventRecorder;

  SpaceAssetOwnershipDirectory(
      SpaceRepository spaceRepository,
      SpaceMembershipRepository membershipRepository,
      SpaceMembershipHistoryService membershipHistory,
      AssetOwnershipHistoryService ownershipHistory,
      AuditEventRecorder auditEventRecorder) {
    this.spaceRepository = spaceRepository;
    this.membershipRepository = membershipRepository;
    this.membershipHistory = membershipHistory;
    this.ownershipHistory = ownershipHistory;
    this.auditEventRecorder = auditEventRecorder;
  }

  @Override
  public AssetType assetType() {
    return Space.ASSET_TYPE;
  }

  @Override
  public boolean existsAssetOwnedByGroup(UUID groupId) {
    return false;
  }

  @Override
  public String ownedAssetConflictMessage() {
    return "Die Gruppe besitzt noch Spaces und kann nicht gelöscht werden";
  }

  @Override
  public List<UUID> assetIdsOwnedBy(PermissionSubject owner) {
    if (owner.type() != PermissionSubjectType.USER) {
      return List.of();
    }
    return spaceRepository.findByOwnerId(owner.id()).stream()
        .filter(space -> space.getOrganizationId().equals(owner.organizationId()))
        .filter(space -> !space.isDefault())
        .map(Space::getId)
        .toList();
  }

  /**
   * Hands the space over and, if the new owner holds no membership of their own, admits them as
   * {@code ADMIN} in the same step. {@code SpaceService#transferOwnership} demands that membership
   * for a good reason - an owner who appears in the member list only through a group is a
   * responsible party the list does not name - and a succession that left the space without an
   * owning member would create the very state it is meant to end.
   */
  @Override
  public void transferOwnership(
      UUID assetId, PermissionSubject newOwner, UUID actorUserId, UUID transferId, Instant at) {
    if (newOwner.type() != PermissionSubjectType.USER) {
      throw new ValidationException("Ein Space gehört immer einer natürlichen Person");
    }
    Space space = spaceRepository.findByIdWithMemberships(assetId).orElseThrow();
    UUID previousOwnerId = space.getOwnerId();
    space.transferOwnershipTo(newOwner.id());
    if (!membershipRepository.existsBySpaceIdAndUserId(space.getId(), newOwner.id())) {
      SpaceMembership membership =
          SpaceMembership.ofUser(newOwner.id(), SpaceRole.ADMIN, space.getOrganizationId());
      space.addMembership(membership);
      membershipHistory.recordTransferredIn(membership, actorUserId, transferId, at);
    }
    spaceRepository.save(space);
    ownershipHistory.recordTransferred(
        Space.ASSET_TYPE, space.getId(), newOwner, actorUserId, transferId, at);
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(space.getOrganizationId())
            .actor(actorUserId)
            .type(AuditEventType.ASSET_OWNER_CHANGED)
            .object(AuditObjectType.SPACE, space.getId(), space.getName())
            .before(Map.of("ownerId", previousOwnerId.toString()))
            .after(Map.of("ownerId", newOwner.id().toString(), "transferId", transferId.toString()))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }
}
