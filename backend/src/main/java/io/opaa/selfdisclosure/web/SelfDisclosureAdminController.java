package io.opaa.selfdisclosure.web;

import io.opaa.api.dto.SelfDisclosureSettings;
import io.opaa.api.dto.SelfDisclosureSettingsUpdate;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.selfdisclosure.SelfDisclosureSettingsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The emergency switch and the daily limit of the self-disclosure, for the system admin. */
@RestController
@RequestMapping("/api/v1/admin/self-disclosure/settings")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class SelfDisclosureAdminController {

  private final SelfDisclosureSettingsService settings;

  public SelfDisclosureAdminController(SelfDisclosureSettingsService settings) {
    this.settings = settings;
  }

  @GetMapping
  public SelfDisclosureSettings getSelfDisclosureSettings() {
    return SelfDisclosureResponseMapper.toResponse(settings.current());
  }

  @PutMapping
  public SelfDisclosureSettings updateSelfDisclosureSettings(
      @Caller CurrentUser caller, @RequestBody SelfDisclosureSettingsUpdate update) {
    return SelfDisclosureResponseMapper.toResponse(
        settings.update(
            caller.organizationId(), caller.id(), update.getEnabled(), update.getDailyLimit()));
  }
}
