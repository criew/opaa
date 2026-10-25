package io.opaa.connection.web;

import io.opaa.api.dto.ConnectionLifecycleSettingKey;
import io.opaa.api.dto.ConnectionLifecycleSettingResponse;
import io.opaa.api.dto.ConnectionLifecycleSettingsResponse;
import io.opaa.connection.ConnectionLifecycleAdministration;
import io.opaa.connection.ConnectionLifecycleAdministration.Entry;

/** Maps the thresholds and periods of the connections onto the API. */
final class ConnectionLifecycleSettingsResponseMapper {

  private ConnectionLifecycleSettingsResponseMapper() {}

  static ConnectionLifecycleSettingsResponse toResponse(
      ConnectionLifecycleAdministration.Overview overview) {
    return new ConnectionLifecycleSettingsResponse(
        overview.entries().stream()
            .map(ConnectionLifecycleSettingsResponseMapper::toResponse)
            .toList(),
        overview.minimumGroupSize());
  }

  static ConnectionLifecycleSettingResponse toResponse(Entry entry) {
    return new ConnectionLifecycleSettingResponse(
            ConnectionLifecycleSettingKey.valueOf(entry.setting().name()),
            entry.value(),
            entry.setting().factoryDefault(),
            entry.setting().min(),
            entry.setting().max(),
            entry.overridden())
        .updatedAt(entry.updatedAt());
  }
}
