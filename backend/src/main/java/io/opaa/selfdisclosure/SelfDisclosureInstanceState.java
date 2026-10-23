package io.opaa.selfdisclosure;

import io.opaa.compliance.InstanceDisclosure.StoredValue;
import io.opaa.compliance.InstanceStateSource;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Emergency switch and daily limit of the self-disclosure as the installation holds them. */
@Component
class SelfDisclosureInstanceState implements InstanceStateSource {

  private final SelfDisclosureSettingsService settings;

  SelfDisclosureInstanceState(SelfDisclosureSettingsService settings) {
    this.settings = settings;
  }

  @Override
  public Map<String, StoredValue> storedSettings() {
    SelfDisclosureSettings current = settings.current();
    return Map.of(
        "self_disclosure_settings.enabled",
        new StoredValue(Boolean.toString(current.enabled()), current.updatedAt()),
        "self_disclosure_settings.daily_limit",
        new StoredValue(Integer.toString(current.dailyLimit()), current.updatedAt()));
  }
}
