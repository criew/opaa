package io.opaa.library;

import io.opaa.asset.AssetVisibilityHistoryCause;
import io.opaa.asset.AssetVisibilityHistoryService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The history half of {@link LibraryChanged}'s double bookkeeping. A plain {@code @EventListener}:
 * it runs in the publisher's transaction and rolls back with it.
 */
@Component
class PermissionHistoryListener {

  private final AssetVisibilityHistoryService visibilityHistoryService;

  PermissionHistoryListener(AssetVisibilityHistoryService visibilityHistoryService) {
    this.visibilityHistoryService = visibilityHistoryService;
  }

  @EventListener
  void onLibraryChanged(LibraryChanged event) {
    switch (event.cause()) {
      case EXTERNAL_ACCESS_CHANGED ->
          visibilityHistoryService.recordExternalAccessChanged(
              event.library(),
              event.library().getExternalAccessState(),
              event.library().getExternalAccessExpiresAt(),
              AssetVisibilityHistoryCause.EXTERNAL_ACCESS_CHANGED,
              event.actorUserId());
    }
  }
}
