package io.opaa.chat.web;

import io.opaa.api.dto.LockedAccountChatRetention;
import io.opaa.api.dto.LockedAccountChatRetentionUpdate;
import io.opaa.api.dto.RetentionPause;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.chat.LockedAccountChatCleanupSettings;
import io.opaa.chat.LockedAccountChatCleanupSettings.Setting;
import io.opaa.instance.RetiredRetentionVariables.Pause;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Switch and period of the deletion of the chats of locked local accounts for the system
 * administration; the check of the role lives in {@link LockedAccountChatCleanupSettings}.
 */
@RestController
@RequestMapping("/api/v1/admin/retention/locked-account-chats")
public class LockedAccountChatRetentionController {

  private final LockedAccountChatCleanupSettings settings;

  public LockedAccountChatRetentionController(LockedAccountChatCleanupSettings settings) {
    this.settings = settings;
  }

  @GetMapping
  public LockedAccountChatRetention getLockedAccountChatRetention(@Caller CurrentUser caller) {
    return toResponse(settings.read(caller));
  }

  @PutMapping
  public LockedAccountChatRetention updateLockedAccountChatRetention(
      @Valid @RequestBody LockedAccountChatRetentionUpdate request, @Caller CurrentUser caller) {
    return toResponse(settings.change(caller, request.getEnabled(), request.getDeleteAfterDays()));
  }

  private LockedAccountChatRetention toResponse(Setting setting) {
    return new LockedAccountChatRetention(
        setting.policy().enabled(),
        setting.policy().deleteAfterDays(),
        setting.defaults().enabled(),
        setting.defaults().deleteAfterDays(),
        setting.overridden(),
        pausedBy(settings.pauses(setting)));
  }

  private static List<RetentionPause> pausedBy(List<Pause> pauses) {
    return pauses.stream()
        .map(
            pause ->
                new RetentionPause(pause.variable(), pause.retiredValue(), pause.effectiveValue()))
        .toList();
  }
}
