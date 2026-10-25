package io.opaa.settings;

import io.opaa.api.types.MailEncryption;
import io.opaa.auth.CurrentUser;
import io.opaa.mail.MailSettings;
import io.opaa.mail.MailSettingsService;
import io.opaa.mail.MailSettingsUpdate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The mail server and sender. The password is not exported, not even whether one is stored, and an
 * import leaves this installation's password as it is.
 */
@Component
class MailSettingsSection implements SettingsSection {

  private final MailSettingsService service;

  MailSettingsSection(MailSettingsService service) {
    this.service = service;
  }

  @Override
  public String key() {
    return "mail";
  }

  @Override
  public String label() {
    return "E-Mail-Versand";
  }

  @Override
  public Set<String> tables() {
    return Set.of("mail_settings");
  }

  @Override
  public Map<String, String> keyLabels() {
    return Map.of(
        "enabled",
        "Versand eingeschaltet",
        "host",
        "SMTP-Server",
        "port",
        "Port",
        "username",
        "Zugangsname",
        "encryption",
        "Verschlüsselung",
        "fromAddress",
        "Absenderadresse",
        "fromName",
        "Absendername");
  }

  @Override
  public Set<String> securityRelevantKeys() {
    return Set.of("enabled", "host", "port", "username", "encryption", "fromAddress");
  }

  @Override
  public Map<String, Object> export(CurrentUser actor) {
    MailSettings settings = service.currentSettings();
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("enabled", settings.isEnabled());
    values.put("host", settings.getHost());
    values.put("port", settings.getPort());
    values.put("username", settings.getUsername());
    values.put("encryption", settings.getEncryption().name());
    values.put("fromAddress", settings.getFromAddress());
    values.put("fromName", settings.getFromName());
    return values;
  }

  @Override
  public void apply(CurrentUser actor, SettingsValues values) {
    Integer port = values.optionalLong("port") == null ? null : values.requireInt("port");
    service.updateSettings(
        actor.organizationId(),
        actor.id(),
        new MailSettingsUpdate(
            values.requireBoolean("enabled"),
            values.optionalString("host"),
            port,
            values.optionalString("username"),
            null,
            values.optionalEnum("encryption", MailEncryption.class),
            values.optionalString("fromAddress"),
            values.optionalString("fromName")));
  }
}
