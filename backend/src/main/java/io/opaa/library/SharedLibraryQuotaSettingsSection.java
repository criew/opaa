package io.opaa.library;

import io.opaa.auth.CurrentUser;
import io.opaa.settings.SettingsSection;
import io.opaa.settings.SettingsValues;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The house-wide storage quota per shared library: the value the administration set, {@code null}
 * while the operator's upper limit applies alone. An import is held to the upper limit of the
 * importing installation.
 */
@Component
class SharedLibraryQuotaSettingsSection implements SettingsSection {

  private final SharedLibraryQuotaAdministration administration;

  SharedLibraryQuotaSettingsSection(SharedLibraryQuotaAdministration administration) {
    this.administration = administration;
  }

  @Override
  public String key() {
    return "sharedLibraryQuota";
  }

  @Override
  public String label() {
    return "Speicherkontingent geteilter Bibliotheken";
  }

  @Override
  public Set<String> tables() {
    return Set.of("shared_library_quota_settings");
  }

  @Override
  public Map<String, String> keyLabels() {
    return Map.of(
        SharedLibraryQuotaAdministration.FIELD, "Speicherkontingent je Bibliothek in Bytes");
  }

  @Override
  public Map<String, Object> export(CurrentUser actor) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put(SharedLibraryQuotaAdministration.FIELD, administration.read(actor).ownQuotaBytes());
    return values;
  }

  @Override
  public void apply(CurrentUser actor, SettingsValues values) {
    administration.change(actor, values.optionalLong(SharedLibraryQuotaAdministration.FIELD));
  }
}
