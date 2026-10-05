package io.opaa.connection.web;

import io.opaa.api.dto.ConnectionAuthorizationCompleteRequest;
import io.opaa.api.dto.ConnectionAuthorizationCompleteResponse;
import io.opaa.api.dto.ConnectionAuthorizationStartRequest;
import io.opaa.api.dto.ConnectionAuthorizationStartResponse;
import io.opaa.api.dto.ConnectionRedirectResponse;
import io.opaa.api.dto.PendingSourceConnection;
import io.opaa.api.dto.SourceConnectionResponsibleRef;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.connection.oauth.ConnectionAuthorizationService;
import io.opaa.connection.oauth.ConnectionAuthorizationService.Completed;
import io.opaa.connection.oauth.ConnectionAuthorizationService.LibraryConsent;
import io.opaa.connection.oauth.ConnectionAuthorizationService.Started;
import io.opaa.connection.profile.LibraryConnection.Responsible;
import io.opaa.connection.profile.LibraryConnection.ResponsibleType;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The OAuth consent of the caller, started and completed through the application: every signed-in
 * person, only their own - for their account or a library's source -, and the redirect URI for the
 * system administration. No answer carries a token, a code or a verifier.
 */
@RestController
public class ConnectionAuthorizationController {

  private static final String PATH = "/api/v1/connections/authorizations";

  private final ConnectionAuthorizationService authorizations;

  public ConnectionAuthorizationController(ConnectionAuthorizationService authorizations) {
    this.authorizations = authorizations;
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/api/v1/admin/connection-profiles/oauth-redirect")
  public ConnectionRedirectResponse getConnectionRedirect() {
    return new ConnectionRedirectResponse().redirectUri(authorizations.redirectUri().orElse(null));
  }

  @PostMapping(PATH)
  public ConnectionAuthorizationStartResponse startConnectionAuthorization(
      @Valid @RequestBody ConnectionAuthorizationStartRequest request, @Caller CurrentUser caller) {
    Started started =
        authorizations.start(
            caller,
            request.getProfileId(),
            request.getPurpose(),
            new LibraryConsent(
                request.getLibraryId(),
                Boolean.TRUE.equals(request.getServiceAccountConfirmed()),
                toResponsible(request.getResponsible()),
                Boolean.TRUE.equals(request.getConfirmAccountChange())));
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
                : ConnectedAccountResponseMapper.toResponse(completed.account()))
        .libraryId(completed.libraryId())
        .pendingConnection(
            completed.pending() == null
                ? null
                : new PendingSourceConnection()
                    .id(completed.pending().id())
                    .accountLabel(completed.pending().accountLabel())
                    .expiresAt(completed.pending().expiresAt()));
  }

  private static Responsible toResponsible(SourceConnectionResponsibleRef ref) {
    return ref == null
        ? null
        : new Responsible(ResponsibleType.valueOf(ref.getType().name()), ref.getId());
  }
}
