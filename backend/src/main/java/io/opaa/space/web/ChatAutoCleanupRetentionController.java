package io.opaa.space.web;

import io.opaa.api.dto.ChatAutoCleanupRetention;
import io.opaa.api.dto.ChatAutoCleanupRetentionUpdate;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.space.ChatAutoCleanupSettings;
import io.opaa.space.ChatAutoCleanupSettings.Setting;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The periods of the automatic chat cleanup for the system administration; the check of the role
 * lives in {@link ChatAutoCleanupSettings}.
 */
@RestController
@RequestMapping("/api/v1/admin/retention/chat-auto-cleanup")
public class ChatAutoCleanupRetentionController {

  private final ChatAutoCleanupSettings settings;

  public ChatAutoCleanupRetentionController(ChatAutoCleanupSettings settings) {
    this.settings = settings;
  }

  @GetMapping
  public ChatAutoCleanupRetention getChatAutoCleanupRetention(@Caller CurrentUser caller) {
    return toResponse(settings.read(caller));
  }

  @PutMapping
  public ChatAutoCleanupRetention updateChatAutoCleanupRetention(
      @Valid @RequestBody ChatAutoCleanupRetentionUpdate request, @Caller CurrentUser caller) {
    return toResponse(
        settings.change(caller, request.getArchiveAfterDays(), request.getDeleteAfterDays()));
  }

  private static ChatAutoCleanupRetention toResponse(Setting setting) {
    return new ChatAutoCleanupRetention(
        setting.periods().archiveAfterDays(),
        setting.periods().deleteAfterDays(),
        setting.defaults().archiveAfterDays(),
        setting.defaults().deleteAfterDays(),
        setting.overridden());
  }
}
