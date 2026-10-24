package io.opaa.linktarget;

import io.opaa.compliance.InstanceDisclosure.StoredValue;
import io.opaa.compliance.InstanceStateSource;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The allowed link targets as the installation holds them; "leer" for the delivered empty list. */
@Component
class LinkTargetInstanceState implements InstanceStateSource {

  private final LinkTargetSettingsService settings;

  LinkTargetInstanceState(LinkTargetSettingsService settings) {
    this.settings = settings;
  }

  @Override
  public Map<String, StoredValue> storedSettings() {
    LinkTargetSettings current = settings.current();
    String value =
        current.allowedOrigins().isEmpty() ? "leer" : String.join(", ", current.allowedOrigins());
    return Map.of(
        "link_target_settings.allowed_origins", new StoredValue(value, current.updatedAt()));
  }
}
