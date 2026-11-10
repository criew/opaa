package io.opaa.template;

import io.opaa.compliance.InstanceDisclosure.StoredValue;
import io.opaa.compliance.InstanceStateSource;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The switch "Vorlagen" for the disclosure: as saved, or off without a change time. */
@Component
class DocumentTemplateInstanceState implements InstanceStateSource {

  private final DocumentTemplateSettingsService settings;

  DocumentTemplateInstanceState(DocumentTemplateSettingsService settings) {
    this.settings = settings;
  }

  @Override
  public Map<String, StoredValue> storedSettings() {
    DocumentTemplateSettingsService.View view = settings.current();
    return Map.of(
        "document_template_settings.enabled",
        new StoredValue(String.valueOf(view.enabled()), view.updatedAt()));
  }
}
