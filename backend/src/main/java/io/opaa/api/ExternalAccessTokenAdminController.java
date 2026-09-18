package io.opaa.api;

import io.opaa.api.dto.AdminExternalAccessTokenListResponse;
import io.opaa.api.dto.BlockExternalAccessTokensRequest;
import io.opaa.api.dto.BlockedExternalAccessTokensResponse;
import io.opaa.api.types.ExternalAccessTokenStatus;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ValidationException;
import io.opaa.externalaccess.token.ExternalAccessTokenAdminService;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Bestandsliste of every access token and the two blocking levers, {@code SYSTEM_ADMIN} only.
 *
 * <p>The list takes exactly two filters - state and "runs out within N days" - and no ordering
 * parameter at all. There is no filter by person and no field carrying a usage date; see {@link
 * ExternalAccessTokenAdminService} for why that is a promise of the product rather than a gap.
 */
@RestController
@RequestMapping("/api/v1/admin/external-access/tokens")
public class ExternalAccessTokenAdminController {

  private final ExternalAccessTokenAdminService adminService;
  private final Clock clock;

  public ExternalAccessTokenAdminController(
      ExternalAccessTokenAdminService adminService, Clock clock) {
    this.adminService = adminService;
    this.clock = clock;
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping
  public AdminExternalAccessTokenListResponse listAllExternalAccessTokens(
      @RequestParam(required = false) ExternalAccessTokenStatus status,
      @RequestParam(required = false) Integer expiringWithinDays) {
    if (expiringWithinDays != null && (expiringWithinDays < 1 || expiringWithinDays > 365)) {
      throw new ValidationException("expiringWithinDays muss zwischen 1 und 365 liegen");
    }
    Instant now = clock.instant();
    return new AdminExternalAccessTokenListResponse(
        adminService.list(status, expiringWithinDays).stream()
            .map(view -> ExternalAccessTokenResponseMapper.toAdmin(view, now))
            .toList());
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/{tokenId}/block")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void blockExternalAccessToken(@PathVariable UUID tokenId, @Caller CurrentUser caller) {
    adminService.block(tokenId, caller.id(), caller.organizationId());
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/block-by-owner")
  public BlockedExternalAccessTokensResponse blockExternalAccessTokensOfOwner(
      @Valid @RequestBody BlockExternalAccessTokensRequest request, @Caller CurrentUser caller) {
    return new BlockedExternalAccessTokensResponse(
        adminService.blockAllOf(request.getOwnerUserId(), caller.id(), caller.organizationId()));
  }
}
