package io.opaa.chat;

import io.opaa.auth.CurrentUser;
import io.opaa.settings.SettingsSection;
import io.opaa.settings.SettingsValues;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Switch and period of the deletion of the chats of locked local accounts; {@code null} for both
 * while the factory defaults apply. Both decide how long chats stay and are marked in the preview.
 */
@Component
class LockedAccountChatCleanupSettingsSection implements SettingsSection {

  private final LockedAccountChatCleanupSettings settings;

  LockedAccountChatCleanupSettingsSection(LockedAccountChatCleanupSettings settings) {
    this.settings = settings;
  }

  @Override
  public String key() {
    return "lockedAccountChatCleanup";
  }

  @Override
  public String label() {
    return "Löschung der Chats gesperrter Konten";
  }

  @Override
  public Set<String> tables() {
    return Set.of("locked_account_chat_cleanup_settings");
  }

  @Override
  public Map<String, String> keyLabels() {
    return Map.of(
        "enabled", "Chats nach der Sperre löschen",
        "deleteAfterDays", "Löschen nach Tagen ab der Sperre");
  }

  @Override
  public Set<String> securityRelevantKeys() {
    return Set.of("enabled", "deleteAfterDays");
  }

  @Override
  public Map<String, Object> export(CurrentUser actor) {
    LockedAccountChatCleanupSettings.Setting setting = settings.read(actor);
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("enabled", setting.overridden() ? setting.policy().enabled() : null);
    values.put("deleteAfterDays", setting.overridden() ? setting.policy().deleteAfterDays() : null);
    return values;
  }

  @Override
  public void apply(CurrentUser actor, SettingsValues values) {
    settings.change(
        actor, values.optionalBoolean("enabled"), values.optionalInt("deleteAfterDays"));
  }
}
