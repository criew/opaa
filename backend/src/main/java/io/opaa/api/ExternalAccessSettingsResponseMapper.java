package io.opaa.api;

import io.opaa.api.dto.ExternalAccessSettingsResponse;
import io.opaa.externalaccess.ExternalAccessDefaults;
import io.opaa.externalaccess.ExternalAccessSettingsService.View;

/**
 * Maps the domain's {@link View} onto the generated {@link ExternalAccessSettingsResponse}
 * (ADR-0006: API DTOs are generated from the specification, never hand-written). The delivered
 * default instructions text travels along so the administration can offer "Vorgabetext" without
 * knowing the wording itself.
 */
final class ExternalAccessSettingsResponseMapper {

  private ExternalAccessSettingsResponseMapper() {}

  static ExternalAccessSettingsResponse toResponse(View view) {
    ExternalAccessSettingsResponse response =
        new ExternalAccessSettingsResponse(
            view.values().enabled(),
            view.values().tokenMaxLifetimeDays(),
            view.values().tokenRateLimitPerHour(),
            view.values().allowedCidrs(),
            view.values().massRetrievalAlertThreshold(),
            view.values().serverInstructions(),
            ExternalAccessDefaults.SERVER_INSTRUCTIONS,
            view.updatedAt());
    response.setUpdatedBy(view.updatedBy());
    return response;
  }
}
