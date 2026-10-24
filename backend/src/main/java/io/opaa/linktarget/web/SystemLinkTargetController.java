package io.opaa.linktarget.web;

import io.opaa.api.dto.LinkTargets;
import io.opaa.api.dto.LinkTargetsUpdate;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.linktarget.LinkTargetSettingsService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Replaces the allowed link targets, system admin only. */
@RestController
@RequestMapping("/api/v1/system/link-targets")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class SystemLinkTargetController {

  private final LinkTargetSettingsService settings;

  public SystemLinkTargetController(LinkTargetSettingsService settings) {
    this.settings = settings;
  }

  @PutMapping
  public LinkTargets updateLinkTargets(
      @Caller CurrentUser caller, @Valid @RequestBody LinkTargetsUpdate update) {
    return LinkTargetResponseMapper.toResponse(
        settings.replace(caller.organizationId(), caller.id(), update.getAllowedOrigins()));
  }
}
