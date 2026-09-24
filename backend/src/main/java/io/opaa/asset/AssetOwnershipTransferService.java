package io.opaa.asset;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.api.types.AssetRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.permission.AssetType;
import io.opaa.permission.PermissionSubject;
import io.opaa.permission.SuccessionCaseCloser;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handing one asset to another owner (#1941) - the per-asset counterpart of the transfer that moves
 * everything a subject holds ({@code PermissionTransferService}). Only {@link AssetRole#OWNER} may
 * do it, and only within their own organization.
 *
 * <p>The move itself belongs to {@link AssetShellOwnershipDirectory}, where every owner change of
 * every asset type lives. What this service adds is the decision to allow it: who may hand over,
 * who may take over, and that the Nachfolge record of an asset that had no capable owner is closed
 * naming the caller - this operation <i>is</i> the Übernahme the Nachfolgeliste points at.
 */
@Service
public class AssetOwnershipTransferService {

  private final AssetAuthorization authorization;
  private final AssetShellOwnershipDirectory ownershipDirectory;
  private final AssetGrantService grantService;
  private final UserRepository userRepository;
  private final SuccessionCaseCloser successionCases;

  AssetOwnershipTransferService(
      AssetAuthorization authorization,
      AssetShellOwnershipDirectory ownershipDirectory,
      AssetGrantService grantService,
      UserRepository userRepository,
      SuccessionCaseCloser successionCases) {
    this.authorization = authorization;
    this.ownershipDirectory = ownershipDirectory;
    this.grantService = grantService;
    this.userRepository = userRepository;
    this.successionCases = successionCases;
  }

  /**
   * Hands the asset over. A target that is already the owner changes nothing and is not an error -
   * the operation is idempotent, so a repeated confirmation does not fail after the fact.
   */
  @Transactional
  public void transferOwnership(
      AssetType assetType,
      UUID assetId,
      AssetOwnerType newOwnerType,
      UUID newOwnerId,
      CurrentUser caller) {
    if (newOwnerId == null) {
      throw new ValidationException("ownerId ist erforderlich");
    }
    Asset asset = authorization.load(assetType, assetId, caller.organizationId());
    authorization.requireRole(asset, caller.id(), caller.isSystemAdmin(), AssetRole.OWNER);

    PermissionSubject newOwner;
    if (newOwnerType == AssetOwnerType.GROUP) {
      // The same rule that governs creating an asset in a group's name: the caller is a member,
      // and the group may still receive the owning group's MANAGER grant.
      grantService.requireOwnableGroup(newOwnerId, assetType, caller);
      newOwner = PermissionSubject.group(newOwnerId, caller.organizationId());
    } else {
      requireUserInOrganization(newOwnerId, caller.organizationId());
      newOwner = PermissionSubject.user(newOwnerId, caller.organizationId());
    }

    if (!ownershipDirectory.handOver(asset, newOwner, caller.id())) {
      return;
    }
    grantService.invalidateAfterCommit(assetType, assetId);
    successionCases.closeForAsset(assetType, assetId, caller.id());
  }

  private void requireUserInOrganization(UUID userId, UUID organizationId) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new NotFoundException("Benutzer nicht gefunden"));
    if (!user.getOrganizationId().equals(organizationId)) {
      throw new NotFoundException("Benutzer nicht gefunden");
    }
  }
}
