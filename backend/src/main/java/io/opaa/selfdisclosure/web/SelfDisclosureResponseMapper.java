package io.opaa.selfdisclosure.web;

import io.opaa.api.dto.SelfDisclosureSettings;
import io.opaa.api.dto.SelfDisclosureStatus;
import io.opaa.api.dto.SelfDisclosureStatus.BlockedEnum;
import io.opaa.api.dto.SelfDisclosureStatus.LastResultEnum;

/** Maps the self-disclosure's domain records to their API responses. */
final class SelfDisclosureResponseMapper {

  private SelfDisclosureResponseMapper() {}

  static SelfDisclosureStatus toResponse(io.opaa.selfdisclosure.SelfDisclosureStatus status) {
    return new SelfDisclosureStatus(
            status.enabled(),
            status.dailyLimit(),
            status.completedToday(),
            status.startsToday(),
            status.remainingExports(),
            status.remainingStarts(),
            status.running(),
            status.retryPossible())
        .blocked(status.blocked() == null ? null : BlockedEnum.fromValue(status.blocked().name()))
        .nextPossibleAt(status.nextPossibleAt())
        .lastCompletedAt(status.lastCompletedAt())
        .lastResult(
            status.lastResult() == null ? null : LastResultEnum.fromValue(status.lastResult()));
  }

  static SelfDisclosureSettings toResponse(io.opaa.selfdisclosure.SelfDisclosureSettings settings) {
    return new SelfDisclosureSettings(
        settings.enabled(), settings.dailyLimit(), settings.updatedAt());
  }
}
