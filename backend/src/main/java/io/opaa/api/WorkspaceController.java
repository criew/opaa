package io.opaa.api;

import io.opaa.api.dto.WorkspaceListResponse;
import io.opaa.api.dto.WorkspaceRequest;
import io.opaa.api.dto.WorkspaceResponse;
import io.opaa.auth.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.workspace.WorkspaceService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Profile({"oidc", "basic"})
@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

  private static final String UNKNOWN_ISSUER = "unknown";

  private final WorkspaceService workspaceService;
  private final UserService userService;

  public WorkspaceController(WorkspaceService workspaceService, UserService userService) {
    this.workspaceService = workspaceService;
    this.userService = userService;
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping
  public ResponseEntity<WorkspaceResponse> createWorkspace(
      @Valid @RequestBody WorkspaceRequest request, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    WorkspaceResponse response =
        workspaceService.createWorkspace(
            request, currentUser.getId(), currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @GetMapping
  public List<WorkspaceListResponse> listWorkspaces(@AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return workspaceService.listWorkspaces(currentUser.getId());
  }

  @GetMapping("/{workspaceId}")
  public WorkspaceResponse getWorkspace(
      @PathVariable UUID workspaceId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return workspaceService.getWorkspace(
        workspaceId, currentUser.getId(), currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
  }

  @PutMapping("/{workspaceId}")
  public WorkspaceResponse updateWorkspace(
      @PathVariable UUID workspaceId,
      @Valid @RequestBody WorkspaceRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return workspaceService.updateWorkspace(workspaceId, request, currentUser.getId());
  }

  @DeleteMapping("/{workspaceId}")
  public ResponseEntity<Void> deleteWorkspace(
      @PathVariable UUID workspaceId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    workspaceService.deleteWorkspace(
        workspaceId, currentUser.getId(), currentUser.getSystemRole() == SystemRole.SYSTEM_ADMIN);
    return ResponseEntity.noContent().build();
  }

  private User currentUser(Jwt jwt) {
    String issuer = jwt.getClaimAsString("iss");
    if (issuer == null || issuer.isBlank()) {
      issuer = UNKNOWN_ISSUER;
    }

    return userService
        .findBySubjectAndIssuer(jwt.getSubject(), issuer)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
  }
}
