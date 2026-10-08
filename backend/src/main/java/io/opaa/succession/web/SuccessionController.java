package io.opaa.succession.web;

import io.opaa.api.dto.SuccessionListResponse;
import io.opaa.api.types.SuccessionKind;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.succession.SuccessionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operational list of the lifecycle (#1819, ADR-0036 Entscheidung 6) - three tabs, one
 * mechanic.
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

  public SuccessionController(SuccessionService successionService) {
    this.successionService = successionService;
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
}
