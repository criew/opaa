package io.opaa.directory.sync;

import io.opaa.auth.CurrentUser;
import io.opaa.settings.SettingsSection;
import io.opaa.settings.SettingsValues;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The plausibility threshold of the directory sync, {@code null} while the factory default applies.
 * Applied through {@link DirectorySyncSettingsAdministration#change}, which writes and audits
 * nothing for an unchanged value.
 */
@Component
class DirectorySyncSettingsSection implements SettingsSection {

  private final DirectorySyncSettingsAdministration administration;

  DirectorySyncSettingsSection(DirectorySyncSettingsAdministration administration) {
    this.administration = administration;
  }

  @Override
  public String key() {
    return "directorySync";
  }

  @Override
  public String label() {
    return "Verzeichnisabgleich";
  }

  @Override
  public Set<String> tables() {
    return Set.of("directory_sync_settings");
  }

  @Override
  public Map<String, String> keyLabels() {
    return Map.of(DirectorySyncSettingsAdministration.FIELD, "Plausibilitätsschwelle in Prozent");
  }

  /**
   * A higher threshold lets a run withdraw more memberships and lock more accounts without
   * confirmation, a lower one holds back more runs: either way the brake against a misconfigured
   * directory moves, so every change is marked.
   */
  @Override
  public Set<String> securityRelevantKeys() {
    return Set.of(DirectorySyncSettingsAdministration.FIELD);
  }

  @Override
  public Map<String, Object> export(CurrentUser actor) {
    DirectorySyncSettingsAdministration.Setting current = administration.current(actor);
    Map<String, Object> values = new LinkedHashMap<>();
    values.put(
        DirectorySyncSettingsAdministration.FIELD,
        current.overridden() ? current.changeThresholdPercent() : null);
    return values;
  }

  @Override
  public void apply(CurrentUser actor, SettingsValues values) {
    Long percent = values.optionalLong(DirectorySyncSettingsAdministration.FIELD);
    administration.change(
        actor,
        percent == null
            ? null
            : (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, percent)));
  }
}
