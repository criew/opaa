package io.opaa.settings;

import io.opaa.account.LocalAuthSettings;
import io.opaa.account.LocalAuthSettingsService;
import io.opaa.auth.CurrentUser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The policy of the local account management. The switch of the local accounts is no part of it: it
 * belongs to the sign-in procedure of this installation like its identity providers, and an import
 * switching it off would end the sessions of every local account.
 */
@Component
class LocalAccountsSettingsSection implements SettingsSection {

  private final LocalAuthSettingsService service;

  LocalAccountsSettingsSection(LocalAuthSettingsService service) {
    this.service = service;
  }

  @Override
  public String key() {
    return "localAccounts";
  }

  @Override
  public String label() {
    return "Lokale Benutzerverwaltung";
  }

  @Override
  public Set<String> tables() {
    return Set.of("local_auth_settings");
  }

  @Override
  public Map<String, Object> export(CurrentUser actor) {
    LocalAuthSettings.Values current = service.current().values();
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("selfRegistrationEnabled", current.selfRegistrationEnabled());
    values.put("selfRegistrationAllowedDomains", current.selfRegistrationAllowedDomains());
    values.put("passwordResetEnabled", current.passwordResetEnabled());
    values.put("passwordMinLength", current.passwordMinLength());
    values.put("invitationTokenTtlHours", current.invitationTokenTtlHours());
    values.put("resetTokenTtlMinutes", current.resetTokenTtlMinutes());
    values.put("defaultExpiryDays", current.defaultExpiryDays());
    values.put("inactiveDays", current.inactiveDays());
    return values;
  }

  @Override
  public void apply(CurrentUser actor, SettingsValues values) {
    service.update(
        actor,
        new LocalAuthSettingsService.Update(
            service.current().enabled(),
            values.requireBoolean("selfRegistrationEnabled"),
            values.requireStrings("selfRegistrationAllowedDomains"),
            values.requireBoolean("passwordResetEnabled"),
            values.requireInt("passwordMinLength"),
            values.requireInt("invitationTokenTtlHours"),
            values.requireInt("resetTokenTtlMinutes"),
            values.requireInt("defaultExpiryDays"),
            values.requireInt("inactiveDays")));
  }
}
