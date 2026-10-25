package io.opaa.settings;

import io.opaa.auth.CurrentUser;
import io.opaa.selfdisclosure.SelfDisclosureSettings;
import io.opaa.selfdisclosure.SelfDisclosureSettingsService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** The switch and the daily limit of the self-disclosure. */
@Component
class SelfDisclosureSettingsSection implements SettingsSection {

  private final SelfDisclosureSettingsService service;

  SelfDisclosureSettingsSection(SelfDisclosureSettingsService service) {
    this.service = service;
  }

  @Override
  public String key() {
    return "selfDisclosure";
  }

  @Override
  public String label() {
    return "Selbstauskunft";
  }

  @Override
  public Set<String> tables() {
    return Set.of("self_disclosure_settings");
  }

  @Override
  public Map<String, String> keyLabels() {
    return Map.of("enabled", "Selbstauskunft angeboten", "dailyLimit", "Tagesgrenze");
  }

  @Override
  public Map<String, Object> export(CurrentUser actor) {
    SelfDisclosureSettings current = service.current();
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("enabled", current.enabled());
    values.put("dailyLimit", current.dailyLimit());
    return values;
  }

  @Override
  public void apply(CurrentUser actor, SettingsValues values) {
    service.update(
        actor.organizationId(),
        actor.id(),
        values.requireBoolean("enabled"),
        values.requireInt("dailyLimit"));
  }
}
