package io.opaa.api;

import io.opaa.api.dto.PermissionHistoryRetentionResponse;
import io.opaa.permission.PermissionHistoryRetentionSettings;

/** Entity to response mapping for the rights history's retention setting. */
final class PermissionHistoryRetentionResponseMapper {

  private PermissionHistoryRetentionResponseMapper() {}

  /**
   * {@code lastRunMonth} is deliberately not passed on: {@code lastCutoff} already answers "how far
   * has the deletion got", and the run month only says when the pass that reached it happened.
   */
  static PermissionHistoryRetentionResponse toResponse(
      PermissionHistoryRetentionSettings settings) {
    return new PermissionHistoryRetentionResponse(
            settings.getRetentionMonths(), settings.getUpdatedAt())
        .lastCutoff(settings.getLastCutoff());
  }
}
