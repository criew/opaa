package io.opaa.settings;

import io.opaa.auth.CurrentUser;
import io.opaa.common.FieldValidationException;
import io.opaa.mail.MailTemplate;
import io.opaa.mail.MailTemplateKey;
import io.opaa.mail.MailTemplateService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The overridden mail templates. The list is the whole set: an override the document does not name
 * is reset to the delivered text, an override it names is stored as given.
 */
@Component
class MailTemplatesSection implements SettingsSection {

  static final String OVERRIDES = "overrides";

  private final MailTemplateService service;

  MailTemplatesSection(MailTemplateService service) {
    this.service = service;
  }

  @Override
  public String key() {
    return "mailTemplates";
  }

  @Override
  public String label() {
    return "E-Mail-Vorlagen";
  }

  @Override
  public Set<String> tables() {
    return Set.of("mail_templates");
  }

  @Override
  public Map<String, Object> export(CurrentUser actor) {
    List<Map<String, Object>> overrides = new ArrayList<>();
    for (MailTemplate template : service.overrides()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("key", template.getTemplateKey());
      entry.put("locale", template.getLocale());
      entry.put("subject", template.getSubject());
      entry.put("bodyPlain", template.getBodyPlain());
      entry.put("bodyHtml", template.getBodyHtml());
      overrides.add(entry);
    }
    Map<String, Object> values = new LinkedHashMap<>();
    values.put(OVERRIDES, overrides);
    return values;
  }

  @Override
  public void apply(CurrentUser actor, SettingsValues values) {
    Set<String> named = new HashSet<>();
    for (SettingsValues entry : values.requireObjects(OVERRIDES)) {
      String keyName = entry.requireString("key");
      MailTemplateKey templateKey =
          MailTemplateKey.fromKey(keyName)
              .orElseThrow(
                  () ->
                      FieldValidationException.of(
                          key() + "." + OVERRIDES,
                          SettingsValues.INVALID_TYPE,
                          "Eine E-Mail-Vorlage „" + keyName + "“ gibt es nicht."));
      String locale = entry.requireString("locale");
      if (!named.add(keyName + "/" + locale)) {
        throw FieldValidationException.of(
            key() + "." + OVERRIDES,
            SettingsValues.INVALID_TYPE,
            "Die E-Mail-Vorlage „" + keyName + "“ steht für " + locale + " zweimal in der Datei.");
      }
      service.update(
          actor.organizationId(),
          actor.id(),
          templateKey,
          locale,
          entry.requireString("subject"),
          entry.requireString("bodyPlain"),
          entry.optionalString("bodyHtml"));
    }
    for (MailTemplate stored : service.overrides()) {
      if (!named.contains(stored.getTemplateKey() + "/" + stored.getLocale())) {
        MailTemplateKey.fromKey(stored.getTemplateKey())
            .ifPresent(
                templateKey ->
                    service.reset(
                        actor.organizationId(), actor.id(), templateKey, stored.getLocale()));
      }
    }
  }
}
