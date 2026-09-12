package io.opaa.api;

import io.opaa.api.dto.LocalAuthSettingsResponse;
import io.opaa.api.dto.LocalAuthSettingsUpdateRequest;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.local.LocalAuthSettingsService;
import io.opaa.auth.local.LocalAuthSettingsService.Updated;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The settings of the local account management (ADR-0033, Entscheidungen 3, 4 and 10), {@code
 * SYSTEM_ADMIN} only: the switch, the policy values and whether links are possible.
 */
@RestController
@RequestMapping("/api/v1/admin/local-auth-settings")
public class LocalAuthSettingsController {

  private final LocalAuthSettingsService settingsService;

  public LocalAuthSettingsController(LocalAuthSettingsService settingsService) {
    this.settingsService = settingsService;
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping
  public LocalAuthSettingsResponse getLocalAuthSettings() {
    return LocalUserResponseMapper.toSettings(settingsService.current(), null);
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PutMapping
  public LocalAuthSettingsResponse updateLocalAuthSettings(
      @Valid @RequestBody LocalAuthSettingsUpdateRequest request, @Caller CurrentUser caller) {
    Updated updated =
        settingsService.update(
            caller,
            new LocalAuthSettingsService.Update(
                Boolean.TRUE.equals(request.getEnabled()),
                Boolean.TRUE.equals(request.getSelfRegistrationEnabled()),
                request.getSelfRegistrationAllowedDomains(),
                Boolean.TRUE.equals(request.getPasswordResetEnabled()),
                request.getPasswordMinLength(),
                request.getInvitationTokenTtlHours(),
                request.getResetTokenTtlMinutes(),
                request.getDefaultExpiryDays(),
                request.getInactiveDays()));
    return LocalUserResponseMapper.toSettings(updated.view(), updated.revokedSessions());
  }
}
