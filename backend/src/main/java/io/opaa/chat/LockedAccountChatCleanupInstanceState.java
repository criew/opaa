package io.opaa.chat;

import io.opaa.compliance.InstanceDisclosure.StoredValue;
import io.opaa.compliance.InstanceStateSource;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Switch and period of the deletion of the chats of locked local accounts in force, for the
 * disclosure: the values the administration set with the time of the change, else the factory
 * defaults without one.
 */
@Component
class LockedAccountChatCleanupInstanceState implements InstanceStateSource {

  static final String ENABLED_KEY = "locked_account_chat_cleanup_settings.enabled";
  static final String DAYS_KEY = "locked_account_chat_cleanup_settings.delete_after_days";

  private final LockedAccountChatCleanupSettingRepository repository;

  LockedAccountChatCleanupInstanceState(LockedAccountChatCleanupSettingRepository repository) {
    this.repository = repository;
  }

  @Override
  public Map<String, StoredValue> storedSettings() {
    LockedAccountChatCleanupPolicy policy = LockedAccountChatCleanupPolicy.FACTORY_DEFAULTS;
    Instant changedAt = null;
    var row = repository.findSingleton();
    if (row.isPresent()) {
      policy = row.get().policy();
      changedAt = row.get().getUpdatedAt();
    }
    Map<String, StoredValue> values = new LinkedHashMap<>();
    values.put(ENABLED_KEY, new StoredValue(Boolean.toString(policy.enabled()), changedAt));
    values.put(DAYS_KEY, new StoredValue(Integer.toString(policy.deleteAfterDays()), changedAt));
    return values;
  }
}
