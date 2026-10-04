package io.opaa.connection.web;

import io.opaa.api.dto.ConnectionAuthorizationCompleteRequest;
import io.opaa.api.dto.ConnectionAuthorizationCompleteResponse;
import io.opaa.api.dto.ConnectionAuthorizationStartRequest;
import io.opaa.api.dto.ConnectionAuthorizationStartResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.connection.oauth.ConnectionAuthorizationService;
import io.opaa.connection.oauth.ConnectionAuthorizationService.Completed;
import io.opaa.connection.oauth.ConnectionAuthorizationService.Started;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The OAuth consent of the caller, started and completed through the application: every signed-in
 * person, only their own. No answer carries a token, a code or a verifier.
 */
@RestController
public class ConnectionAuthorizationController {

  private static final String PATH = "/api/v1/connections/authorizations";

  private final ConnectionAuthorizationService authorizations;

  public ConnectionAuthorizationController(ConnectionAuthorizationService authorizations) {
    this.authorizations = authorizations;
  }

  @PostMapping(PATH)
  public ConnectionAuthorizationStartResponse startConnectionAuthorization(
      @Valid @RequestBody ConnectionAuthorizationStartRequest request, @Caller CurrentUser caller) {
    Started started = authorizations.start(caller, request.getProfileId(), request.getPurpose());
    return new ConnectionAuthorizationStartResponse()
        .authorizationUrl(started.authorizationUrl().toString())
        .expiresAt(started.expiresAt());
  }

  @PostMapping(PATH + "/complete")
  public ConnectionAuthorizationCompleteResponse completeConnectionAuthorization(
      @Valid @RequestBody ConnectionAuthorizationCompleteRequest request,
      @Caller CurrentUser caller) {
    Completed completed =
        authorizations.complete(caller, request.getState(), request.getCode(), request.getError());
    return new ConnectionAuthorizationCompleteResponse()
        .purpose(completed.purpose())
        .profileId(completed.profileId())
        .returnTo(completed.returnTo())
        .account(
            completed.account() == null
                ? null
                : ConnectedAccountResponseMapper.toResponse(completed.account()));
  }
}
