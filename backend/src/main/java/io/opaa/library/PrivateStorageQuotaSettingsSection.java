package io.opaa.library;

import io.opaa.auth.CurrentUser;
import io.opaa.settings.SettingsSection;
import io.opaa.settings.SettingsValues;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The house-wide storage quota of private libraries: the value the administration set, {@code null}
 * while the configured default applies, {@code 0} for unlimited.
 */
@Component
class PrivateStorageQuotaSettingsSection implements SettingsSection {

  private final PrivateStorageAdministration administration;

  PrivateStorageQuotaSettingsSection(PrivateStorageAdministration administration) {
    this.administration = administration;
  }

  @Override
  public String key() {
    return "privateStorageQuota";
  }

  @Override
  public String label() {
    return "Speicherkontingent privater Bibliotheken";
  }

  @Override
  public Set<String> tables() {
    return Set.of("private_storage_quota_settings");
  }

  @Override
  public Map<String, Object> export(CurrentUser actor) {
    PrivateStorageAdministration.QuotaSetting setting = administration.read(actor);
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("quotaBytes", setting.overridden() ? setting.quotaBytes() : null);
    return values;
  }

  @Override
  public void apply(CurrentUser actor, SettingsValues values) {
    administration.change(actor, values.optionalLong("quotaBytes"));
  }
}
