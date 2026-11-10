package io.opaa.template;

import io.opaa.auth.CurrentUser;
import io.opaa.settings.SettingsSection;
import io.opaa.settings.SettingsValues;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** The installation switch "Vorlagen"; the templates themselves are content, not settings. */
@Component
class DocumentTemplateSettingsSection implements SettingsSection {

  private final DocumentTemplateSettingsService service;

  DocumentTemplateSettingsSection(DocumentTemplateSettingsService service) {
    this.service = service;
  }

  @Override
  public String key() {
    return "documentTemplates";
  }

  @Override
  public String label() {
    return "Vorlagen";
  }

  @Override
  public Set<String> tables() {
    return Set.of("document_template_settings");
  }

  @Override
  public Map<String, String> keyLabels() {
    return Map.of("enabled", "Vorlagen angeboten");
  }

  @Override
  public Map<String, Object> export(CurrentUser actor) {
    return Map.of("enabled", service.current().enabled());
  }

  @Override
  public void apply(CurrentUser actor, SettingsValues values) {
    service.update(values.requireBoolean("enabled"), actor);
  }
}
