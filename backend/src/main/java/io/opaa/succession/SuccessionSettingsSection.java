package io.opaa.succession;

import io.opaa.auth.CurrentUser;
import io.opaa.settings.SettingsSection;
import io.opaa.settings.SettingsValues;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The aging threshold of the operational list and the days until an agent or a template without a
 * responsible unit is locked, each {@code null} while the factory default applies. Applied through
 * {@link SuccessionSettingsAdministration}, which writes and audits nothing for an unchanged value.
 * Neither widens anything, so neither is marked.
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
    return Set.of("succession_settings", "responsibility_lock_settings");
  }

  @Override
  public Map<String, String> keyLabels() {
    return Map.of(
        SuccessionSettingsAdministration.FIELD,
        "Als lange offen hervorgehoben ab … Monaten",
        SuccessionSettingsAdministration.LOCK_FIELD,
        "Ohne verantwortliche Stelle gesperrt nach … Tagen");
  }

  @Override
  public Map<String, Object> export(CurrentUser actor) {
    SuccessionSettingsAdministration.Setting current = administration.current(actor);
    Map<String, Object> values = new LinkedHashMap<>();
    values.put(
        SuccessionSettingsAdministration.FIELD,
        current.overridden() ? current.agingThresholdMonths() : null);
    values.put(
        SuccessionSettingsAdministration.LOCK_FIELD,
        current.responsibleLockDaysOverridden() ? current.responsibleLockDays() : null);
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
    Long days = values.optionalLong(SuccessionSettingsAdministration.LOCK_FIELD);
    administration.changeResponsibleLockDays(
        actor,
        days == null ? null : (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, days)));
  }
}
