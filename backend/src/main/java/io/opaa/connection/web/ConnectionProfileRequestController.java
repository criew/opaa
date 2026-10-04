package io.opaa.connection.web;

import io.opaa.api.dto.ConnectionProfileRequestCreateRequest;
import io.opaa.api.dto.ConnectionProfileRequestPageResponse;
import io.opaa.api.dto.ConnectionProfileRequestResolveRequest;
import io.opaa.api.dto.ConnectionProfileRequestResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.connection.ConnectorReleaseService;
import io.opaa.connection.request.ConnectionProfileRequestService;
import io.opaa.connection.request.ConnectionProfileRequestService.Submission;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Connection profile requests ("Zugangswunsch"): submitted by whoever may read the profile options,
 * read back by the requester, listed and resolved by the system administration.
 */
@RestController
public class ConnectionProfileRequestController {

  private static final String ADMIN = "/api/v1/admin/connection-profile-requests";

  private final ConnectionProfileRequestService requests;
  private final ConnectorReleaseService release;

  public ConnectionProfileRequestController(
      ConnectionProfileRequestService requests, ConnectorReleaseService release) {
    this.requests = requests;
    this.release = release;
  }

  @PostMapping("/api/v1/connection-profile-requests")
  public ResponseEntity<ConnectionProfileRequestResponse> submitConnectionProfileRequest(
      @Valid @RequestBody ConnectionProfileRequestCreateRequest request,
      @Caller CurrentUser caller) {
    release.requireAnyRelease(caller);
    Submission submission =
        requests.submit(
            caller,
            ConnectionProfileResponseMapper.toSourceType(request.getSourceType()),
            request.getServerUrl(),
            request.getReason());
    return ResponseEntity.status(submission.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(ConnectionProfileRequestResponseMapper.toResponse(submission.view()));
  }

  @GetMapping("/api/v1/me/connection-profile-requests")
  public List<ConnectionProfileRequestResponse> listMyConnectionProfileRequests(
      @Caller CurrentUser caller) {
    return requests.mine(caller).stream()
        .map(ConnectionProfileRequestResponseMapper::toResponse)
        .toList();
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping(ADMIN)
  public ConnectionProfileRequestPageResponse listConnectionProfileRequests(
      @RequestParam(required = false) String state,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size,
      @Caller CurrentUser caller) {
    return ConnectionProfileRequestResponseMapper.toResponse(
        requests.page(caller, ConnectionProfileRequestResponseMapper.toState(state), page, size));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PutMapping(ADMIN + "/{requestId}")
  public ConnectionProfileRequestResponse resolveConnectionProfileRequest(
      @PathVariable UUID requestId,
      @Valid @RequestBody ConnectionProfileRequestResolveRequest request,
      @Caller CurrentUser caller) {
    return ConnectionProfileRequestResponseMapper.toResponse(
        requests.resolve(
            caller,
            requestId,
            ConnectionProfileRequestResponseMapper.toState(request.getState()),
            request.getProfileId(),
            request.getAnswer()));
  }
}
