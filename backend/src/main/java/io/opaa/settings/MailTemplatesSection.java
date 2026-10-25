package io.opaa.settings;

import io.opaa.auth.CurrentUser;
import io.opaa.common.FieldValidationException;
import io.opaa.common.FieldValidationException.FieldError;
import io.opaa.mail.MailTemplate;
import io.opaa.mail.MailTemplateKey;
import io.opaa.mail.MailTemplateService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The mail templates of the delivered locale, one key per template: {@code null} while the
 * delivered text applies, otherwise the override with subject, plain text and HTML part. Importing
 * {@code null} resets an override, an object stores it as given.
 */
@Component
class MailTemplatesSection implements SettingsSection {

  static final Set<String> FIELDS = Set.of("subject", "bodyPlain", "bodyHtml");

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
  public Map<String, String> keyLabels() {
    Map<String, String> labels = new LinkedHashMap<>();
    for (MailTemplateKey templateKey : MailTemplateKey.values()) {
      labels.put(templateKey.key(), templateKey.label());
    }
    return labels;
  }

  /** A template carries the links of invitations and resets; another text can lead elsewhere. */
  @Override
  public Set<String> securityRelevantKeys() {
    return keyLabels().keySet();
  }

  @Override
  public Map<String, Object> export(CurrentUser actor) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (MailTemplateKey templateKey : MailTemplateKey.values()) {
      values.put(templateKey.key(), null);
    }
    for (MailTemplate template : service.overrides()) {
      if (MailTemplateService.DEFAULT_LOCALE.equals(template.getLocale())
          && values.containsKey(template.getTemplateKey())) {
        Map<String, Object> override = new LinkedHashMap<>();
        override.put("subject", template.getSubject());
        override.put("bodyPlain", template.getBodyPlain());
        override.put("bodyHtml", template.getBodyHtml());
        values.put(template.getTemplateKey(), override);
      }
    }
    return values;
  }

  @Override
  public void apply(CurrentUser actor, SettingsValues values) {
    for (MailTemplateKey templateKey : MailTemplateKey.values()) {
      SettingsValues override = values.optionalObject(templateKey.key(), FIELDS);
      if (override == null) {
        service.reset(
            actor.organizationId(), actor.id(), templateKey, MailTemplateService.DEFAULT_LOCALE);
        continue;
      }
      try {
        service.update(
            actor.organizationId(),
            actor.id(),
            templateKey,
            MailTemplateService.DEFAULT_LOCALE,
            override.optionalString("subject"),
            override.optionalString("bodyPlain"),
            override.optionalString("bodyHtml"));
      } catch (FieldValidationException refused) {
        throw new FieldValidationException(
            refused.getMessage(),
            refused.fieldErrors().stream()
                .map(
                    error ->
                        new FieldError(
                            templateKey.key() + "." + error.field(), error.code(), error.message()))
                .toList());
      }
    }
  }
}
