package io.opaa.api;

import io.opaa.api.dto.ExternalAccessSettingsResponse;
import io.opaa.api.dto.ExternalAccessSettingsUpdateRequest;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.externalaccess.ExternalAccessSettingsService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The channel settings of the external access (#1717, ADR-0035), {@code SYSTEM_ADMIN} only: the
 * installation-wide switch, the token lifetime ceiling, the per-token quota, the networks of the
 * channel, the threshold of the mass retrieval alert and the instructions text for foreign tools.
 */
@RestController
@RequestMapping("/api/v1/system/external-access")
public class ExternalAccessSettingsController {

  private final ExternalAccessSettingsService settingsService;

  public ExternalAccessSettingsController(ExternalAccessSettingsService settingsService) {
    this.settingsService = settingsService;
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping
  public ExternalAccessSettingsResponse getExternalAccessSettings() {
    return ExternalAccessSettingsResponseMapper.toResponse(settingsService.current());
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PutMapping
  public ExternalAccessSettingsResponse updateExternalAccessSettings(
      @Valid @RequestBody ExternalAccessSettingsUpdateRequest request, @Caller CurrentUser caller) {
    return ExternalAccessSettingsResponseMapper.toResponse(
        settingsService.update(
            caller,
            new ExternalAccessSettingsService.Update(
                Boolean.TRUE.equals(request.getEnabled()),
                request.getTokenMaxLifetimeDays(),
                request.getTokenRateLimitPerHour(),
                request.getAllowedCidrs(),
                request.getMassRetrievalAlertThreshold(),
                request.getServerInstructions())));
  }
}
