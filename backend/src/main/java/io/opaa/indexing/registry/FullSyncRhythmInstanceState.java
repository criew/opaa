package io.opaa.indexing.registry;

import io.opaa.compliance.InstanceDisclosure.StoredValue;
import io.opaa.compliance.InstanceStateSource;
import io.opaa.indexing.spi.FullSyncRhythm;
import io.opaa.indexing.spi.FullSyncRhythmSetting;
import io.opaa.indexing.spi.FullSyncRhythmSettingRepository;
import io.opaa.indexing.spi.SourceConnectorDescriptor;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The installation-wide full-sync rhythm in force per source type, for the disclosure: the days the
 * administration set with the time of the change, else the factory default without one. Key {@code
 * full_sync_rhythm_settings.interval_days[<TYPE>]}, one per type with a rhythm.
 */
@Component
class FullSyncRhythmInstanceState implements InstanceStateSource {

  static final String KEY = "full_sync_rhythm_settings.interval_days[%s]";

  private final SourceConnectorRegistry connectors;
  private final FullSyncRhythmSettingRepository repository;

  FullSyncRhythmInstanceState(
      SourceConnectorRegistry connectors, FullSyncRhythmSettingRepository repository) {
    this.connectors = connectors;
    this.repository = repository;
  }

  @Override
  public Map<String, StoredValue> storedSettings() {
    Map<String, FullSyncRhythmSetting> set = new HashMap<>();
    for (FullSyncRhythmSetting setting : repository.findAll()) {
      set.put(setting.getSourceType(), setting);
    }
    Map<String, StoredValue> values = new LinkedHashMap<>();
    for (SourceConnectorDescriptor descriptor : connectors.descriptors()) {
      if (descriptor.fullSyncInterval() == null) {
        continue;
      }
      FullSyncRhythmSetting own = set.get(descriptor.type().key());
      values.put(
          KEY.formatted(descriptor.type().key()),
          own == null
              ? new StoredValue(
                  Integer.toString(FullSyncRhythm.wholeDays(descriptor.fullSyncInterval())), null)
              : new StoredValue(Integer.toString(own.getIntervalDays()), own.getUpdatedAt()));
    }
    return values;
  }
}
