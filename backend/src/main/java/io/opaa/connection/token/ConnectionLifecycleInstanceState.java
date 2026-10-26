package io.opaa.connection.token;

import io.opaa.compliance.InstanceDisclosure.StoredValue;
import io.opaa.compliance.InstanceStateSource;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The thresholds and periods of the connections in force, for the disclosure: the value the
 * administration set with the time of the change, else the factory default without one. Key {@code
 * connection_lifecycle_settings.setting_value[<SETTING>]}, one per setting.
 */
@Component
class ConnectionLifecycleInstanceState implements InstanceStateSource {

  static final String KEY = "connection_lifecycle_settings.setting_value[%s]";

  private final ConnectionLifecycleSettings settings;

  ConnectionLifecycleInstanceState(ConnectionLifecycleSettings settings) {
    this.settings = settings;
  }

  @Override
  public Map<String, StoredValue> storedSettings() {
    Map<String, StoredValue> values = new LinkedHashMap<>();
    for (ConnectionLifecycleSetting setting : ConnectionLifecycleSetting.values()) {
      values.put(
          KEY.formatted(setting.name()),
          settings
              .stored(setting)
              .map(row -> new StoredValue(Integer.toString(row.getValue()), row.getUpdatedAt()))
              .orElseGet(() -> new StoredValue(Integer.toString(setting.factoryDefault()), null)));
    }
    return values;
  }
}
