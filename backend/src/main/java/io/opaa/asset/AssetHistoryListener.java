package io.opaa.asset;

import io.opaa.permission.PermissionHistoryService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The permission-history half of {@link AssetGrantChanged}'s and {@link AssetChanged}'s double
 * bookkeeping. A plain {@code @EventListener}: it runs in the publisher's transaction and rolls
 * back with it.
 */
@Component
class AssetHistoryListener {

  private final PermissionHistoryService permissionHistoryService;
  private final AssetVisibilityHistoryService visibilityHistoryService;

  AssetHistoryListener(
      PermissionHistoryService permissionHistoryService,
      AssetVisibilityHistoryService visibilityHistoryService) {
    this.permissionHistoryService = permissionHistoryService;
    this.visibilityHistoryService = visibilityHistoryService;
  }

  @EventListener
  void onGrantChanged(AssetGrantChanged event) {
    switch (event.cause()) {
      case GRANTED ->
          permissionHistoryService.recordGrantCreated(event.grant(), event.actorUserId());
      case ROLE_CHANGED ->
          permissionHistoryService.recordGrantRoleChanged(event.grant(), event.actorUserId());
      case REVOKED ->
          permissionHistoryService.recordGrantRevoked(event.grant(), event.actorUserId());
    }
  }

  @EventListener
  void onAssetChanged(AssetChanged event) {
    switch (event.cause()) {
      case CREATED -> visibilityHistoryService.recordCreated(event.asset(), event.actorUserId());
      case VISIBILITY_CHANGED ->
          visibilityHistoryService.recordVisibilityChanged(event.asset(), event.actorUserId());
    }
  }
}
