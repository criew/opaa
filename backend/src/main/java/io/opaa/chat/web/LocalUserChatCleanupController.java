package io.opaa.chat.web;

import io.opaa.api.dto.LocalUserChatCleanupResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.chat.LockedAccountChatCleanup;
import io.opaa.chat.LockedAccountChatCleanupService;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The deletion of a local account's chats after a lock by the administration, as a sub-resource of
 * the local account: read in the single view of the account, held and resumed there. Here and not
 * in {@code io.opaa.account.web}, because the chats belong to the assistant module.
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
    return toResponse(cleanupService.stateOf(caller.organizationId(), id));
  }

  @PutMapping("/hold")
  public LocalUserChatCleanupResponse holdLocalUserChatCleanup(
      @PathVariable UUID id, @Caller CurrentUser caller) {
    return toResponse(cleanupService.hold(caller, id));
  }

  @DeleteMapping("/hold")
  public LocalUserChatCleanupResponse resumeLocalUserChatCleanup(
      @PathVariable UUID id, @Caller CurrentUser caller) {
    return toResponse(cleanupService.resume(caller, id));
  }

  static LocalUserChatCleanupResponse toResponse(LockedAccountChatCleanup state) {
    LocalUserChatCleanupResponse response =
        new LocalUserChatCleanupResponse(state.status(), state.held(), state.deleteAfterDays());
    response.setEarliestDeletionAt(state.earliestDeletionAt());
    return response;
  }
}
