package io.opaa.asset;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.api.types.AssetRole;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.permission.AssetGrant;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.permission.AssetOwnershipHistoryService;
import io.opaa.permission.PermissionHistoryService;
import io.opaa.permission.SuccessionReachGuard;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * The lifecycle of the asset shell for every type: creation, a change of findability, deletion. A
 * change of owner happens only in a transfer and lives with it, in {@link
 * AssetShellOwnershipDirectory}. Audit, history and the frozen reach are applied here and nowhere
 * else; a type calls these from its own create, update and delete paths, inside its own
 * transaction.
 *
 * <p>The rules the shell owns: the creator holds {@link AssetRole#OWNER} and an owning group {@link
 * AssetRole#MANAGER} - never {@code OWNER}, which would grow with every member and could never be
 * downgraded; listing an unlisted asset is refused while the succession is open (ADR-0036,
 * Entscheidung 6); a requested findability is first put to the type's {@link
 * AssetTypeDefinition#requireListedWithinLimits}; clearing it for a lowered limit ({@link
 * #clearListedForLoweredCap}) is never refused.
 *
 * <p><b>How far the asset reaches is no longer the shell's</b> (#1931, ADR-0037): a grant to "Alle
 * Beschaeftigten" is an ordinary grant and runs through {@link AssetGrantService}, including its
 * own succession guard and its own cap check.
 */
@Service
public class AssetShellService {

  private final AssetTypes assetTypes;
  private final AssetGrantService grantService;
  private final AssetGrantRepository grantRepository;
  private final AssetOwnershipHistoryService ownershipHistory;
  private final PermissionHistoryService permissionHistory;
  private final AssetVisibilityHistoryService visibilityHistory;
  private final AuditEventRecorder auditEventRecorder;
  private final ApplicationEventPublisher eventPublisher;
  private final SuccessionReachGuard successionGuard;

  public AssetShellService(
      AssetTypes assetTypes,
      AssetGrantService grantService,
      AssetGrantRepository grantRepository,
      AssetOwnershipHistoryService ownershipHistory,
      PermissionHistoryService permissionHistory,
      AssetVisibilityHistoryService visibilityHistory,
      AuditEventRecorder auditEventRecorder,
      ApplicationEventPublisher eventPublisher,
      SuccessionReachGuard successionGuard) {
    this.assetTypes = assetTypes;
    this.grantService = grantService;
    this.grantRepository = grantRepository;
    this.ownershipHistory = ownershipHistory;
    this.permissionHistory = permissionHistory;
    this.visibilityHistory = visibilityHistory;
    this.auditEventRecorder = auditEventRecorder;
    this.eventPublisher = eventPublisher;
    this.successionGuard = successionGuard;
  }

  /**
   * Records a just-saved asset: the owning group's {@code MANAGER} grant, the ownership interval,
   * the creator's {@code OWNER} grant, and - through {@link AssetChanged} - the first reach
   * interval with the type's creation audit entry.
   *
   * @param createdAuditPayload the "after" payload of the creation audit entry, the type's own.
   */
  public void registerCreated(
      Asset asset, UUID creatorUserId, Map<String, Object> createdAuditPayload) {
    assetTypes.require(asset.getAssetType());
    asset.recordCreatedBy(creatorUserId);
    if (asset.getOwnerType() == AssetOwnerType.GROUP) {
      grantService.grantAtCreation(
          asset,
          AssetGrant.forGroup(
              asset.getAssetType(),
              asset.getId(),
              asset.getOrganizationId(),
              asset.getOwnerGroupId(),
              AssetRole.MANAGER,
              null,
              creatorUserId,
              // Ownership, not a release: the growth signal belongs to a granted role.
              null),
          creatorUserId);
    }
    ownershipHistory.recordCreated(
        asset.getAssetType(), asset.getId(), asset.ownerSubject(), creatorUserId);
    grantService.grantAtCreation(
        asset,
        AssetGrant.forUser(
            asset.getAssetType(),
            asset.getId(),
            asset.getOrganizationId(),
            creatorUserId,
            AssetRole.OWNER,
            null,
            creatorUserId),
        creatorUserId);
    eventPublisher.publishEvent(
        new AssetChanged(
            asset, AssetChanged.Cause.CREATED, creatorUserId, null, createdAuditPayload));
  }

  /**
   * Sets findability. Listing an unlisted asset is refused while the asset's succession is open,
   * and the requested state is put to the type's limits before it is applied; a request that
   * changes nothing writes nothing.
   *
   * @return whether {@code listed} actually changed.
   */
  public boolean changeListed(Asset asset, boolean listed, UUID actorUserId) {
    AssetTypeDefinition definition = assetTypes.require(asset.getAssetType());
    boolean previousListed = asset.isListed();
    if (listed && !previousListed) {
      successionGuard.requireAssetReachNotFrozen(
          asset.getAssetType(), asset.getId(), "Eine größere Reichweite (Auffindbarkeit)");
    }
    definition.requireListedWithinLimits(asset, listed);
    asset.applyListed(listed);
    if (listed == previousListed) {
      return false;
    }
    publishListedChanged(asset, previousListed, listed, actorUserId);
    return true;
  }

  /**
   * Takes findability away because the type has just forbidden it - never refused, because it only
   * takes reach away.
   *
   * @return whether {@code listed} actually changed.
   */
  public boolean clearListedForLoweredCap(Asset asset, UUID actorUserId) {
    if (!asset.isListed()) {
      return false;
    }
    asset.applyListed(false);
    publishListedChanged(asset, true, false, actorUserId);
    return true;
  }

  private void publishListedChanged(
      Asset asset, boolean previousListed, boolean listed, UUID actorUserId) {
    eventPublisher.publishEvent(
        new AssetChanged(
            asset,
            AssetChanged.Cause.VISIBILITY_CHANGED,
            actorUserId,
            Map.of("listed", previousListed),
            Map.of("listed", listed)));
  }

  /**
   * Closes every open interval of an asset about to be deleted - its grants, its reach and its
   * ownership - each with a marker naming the actor. The histories carry no foreign key on the
   * asset (ADR-0016), so the deletion would close none of them on its own. Call before the delete;
   * the grants themselves go with the asset ({@code fk_asset_grants_asset_organization}).
   */
  public void registerDeleted(Asset asset, UUID actorUserId) {
    for (AssetGrant grant :
        grantRepository.findByAssetTypeAndAssetId(asset.getAssetType(), asset.getId())) {
      permissionHistory.recordGrantClosedByAssetDeletion(grant, actorUserId);
    }
    visibilityHistory.recordClosedByAssetDeletion(asset, actorUserId);
    ownershipHistory.recordAssetDeleted(
        asset.getAssetType(), asset.getId(), asset.ownerSubject(), actorUserId);
    grantService.invalidateAfterCommit(asset.getAssetType(), asset.getId());
  }
}
