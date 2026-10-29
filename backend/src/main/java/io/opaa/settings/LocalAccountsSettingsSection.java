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
 * switching it off would end the sessions of every local account. The lockout travels as stored,
 * {@code null} for its factory default, and is checked against the limits of the operation of the
 * importing installation.
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
  public Map<String, String> keyLabels() {
    return Map.ofEntries(
        Map.entry("selfRegistrationEnabled", "Selbstregistrierung"),
        Map.entry(
            "selfRegistrationAllowedDomains", "Zulässige Adressdomänen der Selbstregistrierung"),
        Map.entry("passwordResetEnabled", "„Passwort vergessen“"),
        Map.entry("passwordMinLength", "Mindestlänge des Passworts"),
        Map.entry("invitationTokenTtlHours", "Gültigkeit des Einladungslinks in Stunden"),
        Map.entry("resetTokenTtlMinutes", "Gültigkeit des Rücksetzlinks in Minuten"),
        Map.entry("defaultExpiryDays", "Vorgabe für den Kontoablauf in Tagen"),
        Map.entry("inactiveDays", "Inaktivität bis zur Sperre in Tagen"),
        Map.entry("lockoutMaxAttempts", "Fehlversuche bis zur Sperre"),
        Map.entry("lockoutDurationMinutes", "Dauer der Sperre nach Fehlversuchen in Minuten"));
  }

  @Override
  public Set<String> securityRelevantKeys() {
    return Set.of(
        "selfRegistrationEnabled",
        "selfRegistrationAllowedDomains",
        "passwordMinLength",
        "invitationTokenTtlHours",
        "resetTokenTtlMinutes",
        "lockoutMaxAttempts",
        "lockoutDurationMinutes");
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
    values.put("lockoutMaxAttempts", current.lockoutMaxAttempts());
    values.put("lockoutDurationMinutes", current.lockoutDurationMinutes());
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
            values.requireInt("inactiveDays"),
            values.optionalInt("lockoutMaxAttempts"),
            values.optionalInt("lockoutDurationMinutes")));
  }
}
