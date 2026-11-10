package io.opaa.template;

import io.opaa.api.types.Capability;
import io.opaa.permission.RuntimeCapabilitySwitch;
import org.springframework.stereotype.Component;

/** {@link Capability#CREATE_TEMPLATE} is offered only while the switch "Vorlagen" is on. */
@Component
class DocumentTemplateCapabilitySwitch implements RuntimeCapabilitySwitch {

  private final DocumentTemplateSettingsService settings;

  DocumentTemplateCapabilitySwitch(DocumentTemplateSettingsService settings) {
    this.settings = settings;
  }

  @Override
  public Capability capability() {
    return Capability.CREATE_TEMPLATE;
  }

  @Override
  public boolean offered() {
    return settings.isEnabled();
  }
}
