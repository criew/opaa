package io.opaa.succession.web;

import io.opaa.api.dto.SuccessionCountsResponse;
import io.opaa.api.dto.SuccessionListResponse;
import io.opaa.api.dto.SuccessionSettingsRequest;
import io.opaa.api.dto.SuccessionSettingsResponse;
import io.opaa.api.types.SuccessionKind;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.succession.SuccessionService;
import io.opaa.succession.SuccessionSettingsAdministration;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operational list of the lifecycle (ADR-0036 Entscheidung 6) - three tabs, one mechanic.
 *
 * <p><b>There is deliberately no parameter by previous owner or by acting person</b> (Personalrat
 * E1, Z7), and no sort parameter at all: the order is fixed, oldest first, so no axis can appear by
 * the back door. The staging of the addressee is an angabe in every line, not a filter on the list:
 * the administration sees all of it, whoever is meant to act.
 */
@RestController
@RequestMapping("/api/v1/admin/succession")
public class SuccessionController {

  private final SuccessionService successionService;
  private final SuccessionSettingsAdministration settings;

  public SuccessionController(
      SuccessionService successionService, SuccessionSettingsAdministration settings) {
    this.successionService = successionService;
    this.settings = settings;
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping
  public SuccessionListResponse listSuccessionEntries(
      @RequestParam SuccessionKind kind,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @Caller CurrentUser caller) {
    return SuccessionResponseMapper.toResponse(
        successionService.list(caller.organizationId(), kind, page, size));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/counts")
  public SuccessionCountsResponse countSuccessionEntries(@Caller CurrentUser caller) {
    return SuccessionResponseMapper.toResponse(successionService.counts(caller.organizationId()));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/settings")
  public SuccessionSettingsResponse getSuccessionSettings(@Caller CurrentUser caller) {
    return SuccessionResponseMapper.toResponse(settings.current(caller));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PutMapping("/settings")
  public SuccessionSettingsResponse updateSuccessionSettings(
      @RequestBody SuccessionSettingsRequest request, @Caller CurrentUser caller) {
    return SuccessionResponseMapper.toResponse(
        settings.change(caller, request.getAgingThresholdMonths()));
  }
}
