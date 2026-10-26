package io.opaa.externalaccess;

import io.opaa.library.ExternalAccessReminderLead;
import org.springframework.stereotype.Component;

/** The reminder lead of the library module, read from the settings of the external AI tools. */
@Component
class StoredExternalAccessReminderLead implements ExternalAccessReminderLead {

  private final ExternalAccessSettingsService settings;

  StoredExternalAccessReminderLead(ExternalAccessSettingsService settings) {
    this.settings = settings;
  }

  @Override
  public int days() {
    return settings.releaseReminderLeadDays();
  }
}
