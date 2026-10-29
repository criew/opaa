package io.opaa.space;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.api.types.SpaceRole;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.common.ConflictException;
import io.opaa.common.ValidationException;
import io.opaa.permission.AssetOwnershipDirectory;
import io.opaa.permission.AssetOwnershipHistoryService;
import io.opaa.permission.AssetType;
import io.opaa.permission.PermissionSubject;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The space answer to {@link AssetOwnershipDirectory} - the port through which a succession hands
 * somebody's spaces to their successor without {@code io.opaa.permission} knowing this package.
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
  private final SpaceAccessPolicy accessPolicy;

  SpaceAssetOwnershipDirectory(
      SpaceRepository spaceRepository,
      SpaceMembershipRepository membershipRepository,
      SpaceMembershipHistoryService membershipHistory,
      AssetOwnershipHistoryService ownershipHistory,
      AuditEventRecorder auditEventRecorder,
      SpaceAccessPolicy accessPolicy) {
    this.accessPolicy = accessPolicy;
    this.spaceRepository = spaceRepository;
    this.membershipRepository = membershipRepository;
    this.membershipHistory = membershipHistory;
    this.ownershipHistory = ownershipHistory;
    this.auditEventRecorder = auditEventRecorder;
  }

  @Override
  public boolean answersFor(AssetType assetType) {
    return Space.ASSET_TYPE.equals(assetType);
  }

  @Override
  public boolean existsAssetOwnedByGroup(UUID groupId) {
    return false;
  }

  @Override
  public String ownedAssetConflictMessage(UUID groupId) {
    return "Die Gruppe besitzt noch Spaces und kann nicht gelöscht werden";
  }

  /** A space is owned by a natural person, never by a group. */
  @Override
  public String ownedAssetSummary(UUID groupId, UUID organizationId) {
    return "";
  }

  @Override
  public long countAssetsOwnedBy(PermissionSubject owner) {
    return ownedSpaceIds(owner).size();
  }

  /** A space is owned by a natural person, never by a group - there is nothing to count here. */
  @Override
  public Map<UUID, Long> countAssetsOwnedByGroups(Collection<UUID> groupIds, UUID organizationId) {
    return Map.of();
  }

  @Override
  public Map<AssetType, List<UUID>> assetIdsOwnedBy(PermissionSubject owner) {
    List<UUID> owned = ownedSpaceIds(owner);
    return owned.isEmpty() ? Map.of() : Map.of(Space.ASSET_TYPE, owned);
  }

  private List<UUID> ownedSpaceIds(PermissionSubject owner) {
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
   * A person who owns spaces hands them only to an active account - see {@link #transferOwnership}.
   */
  @Override
  public void requireAcceptableNewOwner(PermissionSubject source, PermissionSubject newOwner) {
    if (newOwner.type() == PermissionSubjectType.USER && !ownedSpaceIds(source).isEmpty()) {
      requireActive(newOwner);
    }
  }

  private void requireActive(PermissionSubject newOwner) {
    if (!accessPolicy.isActiveAccount(newOwner.id())) {
      throw new ConflictException(
          "Das ausgewählte Konto ist nicht aktiv und kann die Verantwortung für Spaces nicht"
              + " übernehmen");
    }
  }

  /**
   * Hands the space over and, if the new owner holds no membership of their own, admits them as
   * {@code ADMIN} in the same step. {@code SpaceService#transferOwnership} demands that membership
   * for a good reason - an owner who appears in the member list only through a group is a
   * responsible party the list does not name - and a succession that left the space without an
   * owning member would create the very state it is meant to end. For the same reason the new owner
   * needs an active account (409 otherwise). An archived space goes along deliberately: left
   * behind, it would stay without anybody answering for it.
   */
  @Override
  public void transferOwnership(
      AssetType assetType,
      UUID assetId,
      PermissionSubject newOwner,
      UUID actorUserId,
      UUID transferId,
      Instant at) {
    if (newOwner.type() != PermissionSubjectType.USER) {
      throw new ValidationException("Ein Space gehört immer einer natürlichen Person");
    }
    Space space = spaceRepository.findByIdWithMemberships(assetId).orElseThrow();
    if (space.isDefault()) {
      throw new ValidationException("Der Standard-Space kann nicht übertragen werden");
    }
    requireActive(newOwner);
    PermissionSubject previousOwner = space.ownerSubject();
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
            .object(AuditObjectType.SPACE, space.getId(), space.auditName())
            .before(Map.of("ownerId", previousOwner.auditRef(auditEventRecorder).toString()))
            .after(
                Map.of(
                    "ownerId",
                    newOwner.auditRef(auditEventRecorder).toString(),
                    "transferId",
                    transferId.toString()))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }
}
