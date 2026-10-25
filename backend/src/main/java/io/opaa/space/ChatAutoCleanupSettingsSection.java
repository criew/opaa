package io.opaa.space;

import io.opaa.auth.CurrentUser;
import io.opaa.settings.SettingsSection;
import io.opaa.settings.SettingsValues;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The periods of the automatic chat cleanup; {@code null} for both while the factory defaults
 * apply. A shorter period deletes chats earlier and is marked in the preview.
 */
@Component
class ChatAutoCleanupSettingsSection implements SettingsSection {

  private final ChatAutoCleanupSettings settings;

  ChatAutoCleanupSettingsSection(ChatAutoCleanupSettings settings) {
    this.settings = settings;
  }

  @Override
  public String key() {
    return "chatAutoCleanup";
  }

  @Override
  public String label() {
    return "Fristen der Chat-Bereinigung in Spaces";
  }

  @Override
  public Set<String> tables() {
    return Set.of("chat_auto_cleanup_settings");
  }

  @Override
  public Map<String, String> keyLabels() {
    return Map.of(
        "archiveAfterDays", "Archivieren nach Tagen ohne Aktivität",
        "deleteAfterDays", "Löschen nach Tagen im Archiv");
  }

  @Override
  public Set<String> securityRelevantKeys() {
    return Set.of("archiveAfterDays", "deleteAfterDays");
  }

  @Override
  public Map<String, Object> export(CurrentUser actor) {
    ChatAutoCleanupSettings.Setting setting = settings.read(actor);
    Map<String, Object> values = new LinkedHashMap<>();
    values.put(
        "archiveAfterDays", setting.overridden() ? setting.periods().archiveAfterDays() : null);
    values.put(
        "deleteAfterDays", setting.overridden() ? setting.periods().deleteAfterDays() : null);
    return values;
  }

  @Override
  public void apply(CurrentUser actor, SettingsValues values) {
    settings.change(
        actor, values.optionalInt("archiveAfterDays"), values.optionalInt("deleteAfterDays"));
  }
}
