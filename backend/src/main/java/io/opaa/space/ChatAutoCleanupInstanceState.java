package io.opaa.space;

import io.opaa.compliance.InstanceDisclosure.StoredValue;
import io.opaa.compliance.InstanceStateSource;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The periods of the automatic chat cleanup in force, for the disclosure: the values the
 * administration set with the time of the change, else the factory defaults without one.
 */
@Component
class ChatAutoCleanupInstanceState implements InstanceStateSource {

  static final String ARCHIVE_KEY = "chat_auto_cleanup_settings.archive_after_days";
  static final String DELETE_KEY = "chat_auto_cleanup_settings.delete_after_days";

  private final ChatAutoCleanupSettingRepository repository;

  ChatAutoCleanupInstanceState(ChatAutoCleanupSettingRepository repository) {
    this.repository = repository;
  }

  @Override
  public Map<String, StoredValue> storedSettings() {
    ChatAutoCleanupPeriods periods = ChatAutoCleanupPeriods.FACTORY_DEFAULTS;
    Instant changedAt = null;
    var row = repository.findSingleton();
    if (row.isPresent()) {
      periods = row.get().periods();
      changedAt = row.get().getUpdatedAt();
    }
    Map<String, StoredValue> values = new LinkedHashMap<>();
    values.put(
        ARCHIVE_KEY, new StoredValue(Integer.toString(periods.archiveAfterDays()), changedAt));
    values.put(DELETE_KEY, new StoredValue(Integer.toString(periods.deleteAfterDays()), changedAt));
    return values;
  }
}
