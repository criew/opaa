package io.opaa.chat.web;

import io.opaa.api.dto.LocalUserChatCleanupHold;
import io.opaa.api.dto.LocalUserChatCleanupHoldRequest;
import io.opaa.api.dto.LocalUserChatCleanupPendingHold;
import io.opaa.api.dto.LocalUserChatCleanupResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.chat.LockedAccountChatCleanup;
import io.opaa.chat.LockedAccountChatCleanupService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The deletion of a local account's chats after a lock by the administration, as a sub-resource of
 * the local account: read in the single view of the account, held there by request and approval of
 * two administrators, and resumed. Here and not in {@code io.opaa.account.web}, because the chats
 * belong to the assistant module.
 */
@RestController
@RequestMapping("/api/v1/admin/local-users/{id}/chat-cleanup")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class LocalUserChatCleanupController {

  private final LockedAccountChatCleanupService cleanupService;

  public LocalUserChatCleanupController(LockedAccountChatCleanupService cleanupService) {
    this.cleanupService = cleanupService;
  }

  @GetMapping
  public LocalUserChatCleanupResponse getLocalUserChatCleanup(
      @PathVariable UUID id, @Caller CurrentUser caller) {
    return toResponse(cleanupService.stateOf(caller.organizationId(), id), caller);
  }

  @PostMapping("/hold-request")
  public LocalUserChatCleanupResponse requestLocalUserChatCleanupHold(
      @PathVariable UUID id,
      @Valid @RequestBody LocalUserChatCleanupHoldRequest request,
      @Caller CurrentUser caller) {
    return toResponse(
        cleanupService.requestHold(caller, id, request.getPurpose(), request.getReason()), caller);
  }

  @PostMapping("/hold-request/approve")
  public LocalUserChatCleanupResponse approveLocalUserChatCleanupHold(
      @PathVariable UUID id, @Caller CurrentUser caller) {
    return toResponse(cleanupService.approveHold(caller, id), caller);
  }

  @DeleteMapping("/hold-request")
  public LocalUserChatCleanupResponse rejectLocalUserChatCleanupHold(
      @PathVariable UUID id, @Caller CurrentUser caller) {
    return toResponse(cleanupService.rejectHold(caller, id), caller);
  }

  @DeleteMapping("/hold")
  public LocalUserChatCleanupResponse resumeLocalUserChatCleanup(
      @PathVariable UUID id, @Caller CurrentUser caller) {
    return toResponse(cleanupService.resume(caller, id), caller);
  }

  static LocalUserChatCleanupResponse toResponse(
      LockedAccountChatCleanup state, CurrentUser caller) {
    LocalUserChatCleanupResponse response =
        new LocalUserChatCleanupResponse(
            state.status(), state.held(), state.deleteAfterDays(), state.holdMaxDays());
    response.setEarliestDeletionAt(state.earliestDeletionAt());
    if (state.hold() != null) {
      LockedAccountChatCleanup.Hold hold = state.hold();
      response.setHold(
          new LocalUserChatCleanupHold()
              .purpose(hold.purpose())
              .reason(hold.reason())
              .heldSince(hold.heldSince())
              .heldUntil(hold.heldUntil()));
    }
    if (state.pendingRequest() != null) {
      LockedAccountChatCleanup.PendingRequest pending = state.pendingRequest();
      response.setPendingRequest(
          new LocalUserChatCleanupPendingHold()
              .purpose(pending.purpose())
              .reason(pending.reason())
              .requestedAt(pending.requestedAt())
              .requestedByMe(caller.id().equals(pending.requestedBy()))
              .extension(pending.extension()));
    }
    return response;
  }
}
