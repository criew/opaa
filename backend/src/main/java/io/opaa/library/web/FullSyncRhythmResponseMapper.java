package io.opaa.library.web;

import io.opaa.api.dto.FullSyncRhythmResponse;
import io.opaa.library.FullSyncRhythmAdministration.Rhythm;

/** Maps the installation-wide full-sync rhythm of a source type onto the API. */
final class FullSyncRhythmResponseMapper {

  private FullSyncRhythmResponseMapper() {}

  static FullSyncRhythmResponse toResponse(Rhythm rhythm) {
    return new FullSyncRhythmResponse(
        rhythm.type().key(),
        rhythm.displayName(),
        rhythm.intervalDays(),
        rhythm.defaultIntervalDays(),
        rhythm.overridden());
  }
}
