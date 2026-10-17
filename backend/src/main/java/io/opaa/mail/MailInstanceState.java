package io.opaa.mail;

import io.opaa.compliance.InstanceStateSource;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Whether this instance sends mail, for the disclosure; nothing of the server or its account. */
@Component
class MailInstanceState implements InstanceStateSource {

  private final MailSettingsRepository repository;

  MailInstanceState(MailSettingsRepository repository) {
    this.repository = repository;
  }

  @Override
  public Map<String, Boolean> dataFlows() {
    return Map.of("mail", repository.findSingleton().map(MailSettings::isEnabled).orElse(false));
  }
}
