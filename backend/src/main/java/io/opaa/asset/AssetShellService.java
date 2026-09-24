package io.opaa.asset;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AssetVisibility;
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
 * The lifecycle of the asset shell for every type: creation, a change of release level or
 * findability, deletion. A change of owner happens only in a transfer and lives with it, in {@link
 * AssetShellOwnershipDirectory}. Audit, history and the frozen reach are applied here and nowhere
 * else; a type calls these from its own create, update and delete paths, inside its own
 * transaction.
 *
 * <p>The rules the shell owns: the creator holds {@link AssetRole#OWNER} and an owning group {@link
 * AssetRole#MANAGER} - never {@code OWNER}, which would grow with every member and could never be
 * downgraded; a widening of reach is refused while the succession is open (ADR-0036, Entscheidung
 * 6); every requested reach is first put to the type's {@link
 * AssetTypeDefinition#requireReachWithinLimits}; narrowing to a lowered limit ({@link
 * #narrowReachTo}) is never refused.
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
   * Sets release level and findability. A widening is refused while the asset's succession is open,
   * and every requested state is put to the type's limits before it is applied; a request that
   * changes nothing writes nothing.
   *
   * @param visibility the requested visibility, {@code null} to keep the current one.
   * @return whether visibility or listed actually changed.
   */
  public boolean changeReach(
      Asset asset, AssetVisibility visibility, boolean listed, UUID actorUserId) {
    AssetTypeDefinition definition = assetTypes.require(asset.getAssetType());
    AssetVisibility previousVisibility = asset.getVisibility();
    boolean previousListed = asset.isListed();
    AssetVisibility effectiveVisibility = visibility != null ? visibility : previousVisibility;
    if (widensReach(asset, visibility, listed)) {
      successionGuard.requireAssetReachNotFrozen(
          asset.getAssetType(),
          asset.getId(),
          "Eine größere Reichweite (Sichtbarkeit oder Auffindbarkeit)");
    }
    definition.requireReachWithinLimits(asset, effectiveVisibility, listed);
    asset.applyReach(effectiveVisibility, listed);
    if (effectiveVisibility == previousVisibility && listed == previousListed) {
      return false;
    }
    eventPublisher.publishEvent(
        new AssetChanged(
            asset,
            AssetChanged.Cause.VISIBILITY_CHANGED,
            actorUserId,
            Map.of("visibility", previousVisibility.name(), "listed", previousListed),
            Map.of("visibility", effectiveVisibility.name(), "listed", listed)));
    return true;
  }

  /**
   * Narrows release level and findability to a limit the type has just lowered - never refused,
   * because it only takes reach away.
   *
   * @return whether visibility or listed actually changed.
   */
  public boolean narrowReachTo(
      Asset asset, AssetVisibility visibilityLimit, boolean listedAllowed, UUID actorUserId) {
    AssetVisibility previousVisibility = asset.getVisibility();
    boolean previousListed = asset.isListed();
    AssetVisibility visibility =
        previousVisibility.exceeds(visibilityLimit) ? visibilityLimit : previousVisibility;
    boolean listed = previousListed && listedAllowed;
    if (visibility == previousVisibility && listed == previousListed) {
      return false;
    }
    asset.applyReach(visibility, listed);
    eventPublisher.publishEvent(
        new AssetChanged(
            asset,
            AssetChanged.Cause.VISIBILITY_CHANGED,
            actorUserId,
            Map.of("visibility", previousVisibility.name(), "listed", previousListed),
            Map.of("visibility", visibility.name(), "listed", listed)));
    return true;
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

  /**
   * Whether the request reaches further than the asset does today - a visibility moving up to
   * {@code ORGANIZATION}, or listing an unlisted asset. Renaming and narrowing never widen.
   */
  private static boolean widensReach(Asset asset, AssetVisibility requested, boolean listed) {
    boolean widerVisibility =
        requested != null
            && requested != asset.getVisibility()
            && requested == AssetVisibility.ORGANIZATION;
    return widerVisibility || (listed && !asset.isListed());
  }
}
