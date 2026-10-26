package io.opaa.succession;

import io.opaa.auth.CurrentUser;
import io.opaa.settings.SettingsSection;
import io.opaa.settings.SettingsValues;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The aging threshold of the operational list, {@code null} while the factory default applies.
 * Applied through {@link SuccessionSettingsAdministration#change}, which writes and audits nothing
 * for an unchanged value. It highlights and widens nothing, so it is not marked.
 */
@Component
class SuccessionSettingsSection implements SettingsSection {

  private final SuccessionSettingsAdministration administration;

  SuccessionSettingsSection(SuccessionSettingsAdministration administration) {
    this.administration = administration;
  }

  @Override
  public String key() {
    return "succession";
  }

  @Override
  public String label() {
    return "Ohne Zuständigkeit";
  }

  @Override
  public Set<String> tables() {
    return Set.of("succession_settings");
  }

  @Override
  public Map<String, String> keyLabels() {
    return Map.of(
        SuccessionSettingsAdministration.FIELD, "Als lange offen hervorgehoben ab … Monaten");
  }

  @Override
  public Map<String, Object> export(CurrentUser actor) {
    SuccessionSettingsAdministration.Setting current = administration.current(actor);
    Map<String, Object> values = new LinkedHashMap<>();
    values.put(
        SuccessionSettingsAdministration.FIELD,
        current.overridden() ? current.agingThresholdMonths() : null);
    return values;
  }

  @Override
  public void apply(CurrentUser actor, SettingsValues values) {
    Long months = values.optionalLong(SuccessionSettingsAdministration.FIELD);
    administration.change(
        actor,
        months == null
            ? null
            : (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, months)));
  }
}
