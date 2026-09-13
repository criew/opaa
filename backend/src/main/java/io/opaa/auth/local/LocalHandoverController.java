package io.opaa.auth.local;

import io.opaa.api.dto.LocalHandoverCodeRequest;
import io.opaa.api.dto.LocalHandoverPreviewResponse;
import io.opaa.api.dto.LocalHandoverProvider;
import io.opaa.api.dto.LocalHandoverRedeemRequest;
import io.opaa.api.dto.LocalHandoverScope;
import io.opaa.auth.local.LocalHandoverAccountService.HandoverPreview;
import io.opaa.auth.oidc.OidcProvider;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two endpoints the person redeeming a handover calls (ADR-0033, Entscheidung 12), both
 * unauthenticated and both gated by the single-use code from the link: what the handover would
 * move, and the handover itself. The provider's access token arrives in the body of {@code redeem}
 * - see {@link LocalHandoverService} for why it must not be an {@code Authorization} header. Exists
 * in the {@code oidc} profile only.
 */
@RestController
@RequestMapping("/api/v1/auth/local/handover")
@Profile("oidc")
public class LocalHandoverController {

  private final LocalHandoverService handover;

  public LocalHandoverController(LocalHandoverService handover) {
    this.handover = handover;
  }

  @PostMapping("/preview")
  public LocalHandoverPreviewResponse preview(
      @Valid @RequestBody LocalHandoverCodeRequest request) {
    return toResponse(handover.preview(request.getToken()));
  }

  @PostMapping("/redeem")
  public ResponseEntity<Void> redeem(@Valid @RequestBody LocalHandoverRedeemRequest request) {
    handover.redeem(request.getToken(), request.getProviderToken());
    return ResponseEntity.noContent().build();
  }

  /** Never the address and never a subject: the page names the account by its display name. */
  private static LocalHandoverPreviewResponse toResponse(HandoverPreview preview) {
    OidcProvider provider = preview.provider();
    LocalHandoverScope scope =
        new LocalHandoverScope(
            (int) preview.scope().spaceMemberships(),
            (int) preview.scope().groupMemberships(),
            preview.scope().systemRole());
    scope.setPersonalSpaceName(preview.scope().personalSpaceName());
    return new LocalHandoverPreviewResponse(
        preview.user().getDisplayName(),
        preview.reason(),
        new LocalHandoverProvider(provider.getId(), provider.getDisplayName()),
        scope,
        preview.expiresAt());
  }
}
