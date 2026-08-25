package io.opaa.library;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The permission-history half of {@link GrantChanged}/{@link LibraryChanged}'s double bookkeeping -
 * see their Javadoc for the transaction contract shared with {@link AuditListener}. A default
 * {@code @EventListener} (not {@code @TransactionalEventListener}) for the same reason as {@link
 * AuditListener}: it must run in the publisher's own transaction, not after commit, so it rolls
 * back with the triggering operation exactly like the direct {@link PermissionHistoryService} calls
 * it replaces.
 */
@Component
class PermissionHistoryListener {

  private final PermissionHistoryService permissionHistoryService;

  PermissionHistoryListener(PermissionHistoryService permissionHistoryService) {
    this.permissionHistoryService = permissionHistoryService;
  }

  @EventListener
  void onGrantChanged(GrantChanged event) {
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
  void onLibraryChanged(LibraryChanged event) {
    switch (event.cause()) {
      case CREATED ->
          permissionHistoryService.recordLibraryCreated(event.library(), event.actorUserId());
      case VISIBILITY_CHANGED ->
          permissionHistoryService.recordVisibilityChanged(event.library(), event.actorUserId());
    }
  }
}
