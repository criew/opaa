package io.opaa.api;

import io.opaa.api.dto.CreateExternalAccessTokenRequest;
import io.opaa.api.dto.CreatedExternalAccessTokenResponse;
import io.opaa.api.dto.EligibleExternalAccessLibraryListResponse;
import io.opaa.api.dto.OwnExternalAccessTokenListResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.externalaccess.token.ExternalAccessTokenService;
import io.opaa.externalaccess.token.ExternalAccessTokenService.IssuedExternalAccessToken;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The person's own access tokens (ADR-0035, Entscheidung 2): issue, list, revoke - and the set of
 * libraries an issuance accepts.
 *
 * <p>There is deliberately <b>no update path</b>. The library selection of an issued token cannot
 * be changed - a change is a new token - so an attempt reaches no handler and is answered 405/404
 * by the framework rather than by a check someone could forget.
 */
@RestController
@RequestMapping("/api/v1/external-access")
public class ExternalAccessTokenController {

  private final ExternalAccessTokenService tokenService;
  private final Clock clock;

  public ExternalAccessTokenController(ExternalAccessTokenService tokenService, Clock clock) {
    this.tokenService = tokenService;
    this.clock = clock;
  }

  @GetMapping("/tokens")
  public OwnExternalAccessTokenListResponse listOwnExternalAccessTokens(
      @Caller CurrentUser caller) {
    Instant now = clock.instant();
    return new OwnExternalAccessTokenListResponse(
        tokenService.listOwn(caller.id()).stream()
            .map(view -> ExternalAccessTokenResponseMapper.toOwn(view, now))
            .toList());
  }

  @GetMapping("/eligible-libraries")
  public EligibleExternalAccessLibraryListResponse listEligibleExternalAccessLibraries(
      @Caller CurrentUser caller) {
    return new EligibleExternalAccessLibraryListResponse(
        tokenService.eligibleLibraries(caller.id(), caller.organizationId()).stream()
            .map(ExternalAccessTokenResponseMapper::toEligible)
            .toList());
  }

  @PostMapping("/tokens")
  @ResponseStatus(HttpStatus.CREATED)
  public CreatedExternalAccessTokenResponse createExternalAccessToken(
      @Valid @RequestBody CreateExternalAccessTokenRequest request, @Caller CurrentUser caller) {
    IssuedExternalAccessToken issued =
        tokenService.issue(
            caller.id(),
            caller.organizationId(),
            request.getName(),
            request.getLibraryIds(),
            request.getExpiresAt());
    return ExternalAccessTokenResponseMapper.toCreated(issued, clock.instant());
  }

  @DeleteMapping("/tokens/{tokenId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revokeOwnExternalAccessToken(@PathVariable UUID tokenId, @Caller CurrentUser caller) {
    tokenService.revokeOwn(caller.id(), caller.organizationId(), tokenId);
  }
}
