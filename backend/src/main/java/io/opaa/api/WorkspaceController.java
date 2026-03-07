package io.opaa.api;

import io.opaa.api.dto.WorkspaceListResponse;
import io.opaa.api.dto.WorkspaceRequest;
import io.opaa.api.dto.WorkspaceResponse;
import io.opaa.workspace.WorkspaceService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("!mock")
@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

  private final WorkspaceService workspaceService;

  public WorkspaceController(WorkspaceService workspaceService) {
    this.workspaceService = workspaceService;
  }

  @PostMapping
  public ResponseEntity<WorkspaceResponse> createWorkspace(
      @Valid @RequestBody WorkspaceRequest request,
      @RequestHeader("X-User-Id") UUID currentUserId,
      @RequestHeader(value = "X-System-Admin", defaultValue = "false") boolean systemAdmin) {
    WorkspaceResponse response =
        workspaceService.createWorkspace(request, currentUserId, systemAdmin);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @GetMapping
  public List<WorkspaceListResponse> listWorkspaces(
      @RequestHeader("X-User-Id") UUID currentUserId) {
    return workspaceService.listWorkspaces(currentUserId);
  }

  @GetMapping("/{workspaceId}")
  public WorkspaceResponse getWorkspace(
      @PathVariable UUID workspaceId,
      @RequestHeader("X-User-Id") UUID currentUserId,
      @RequestHeader(value = "X-System-Admin", defaultValue = "false") boolean systemAdmin) {
    return workspaceService.getWorkspace(workspaceId, currentUserId, systemAdmin);
  }

  @PutMapping("/{workspaceId}")
  public WorkspaceResponse updateWorkspace(
      @PathVariable UUID workspaceId,
      @Valid @RequestBody WorkspaceRequest request,
      @RequestHeader("X-User-Id") UUID currentUserId) {
    return workspaceService.updateWorkspace(workspaceId, request, currentUserId);
  }

  @DeleteMapping("/{workspaceId}")
  public ResponseEntity<Void> deleteWorkspace(
      @PathVariable UUID workspaceId,
      @RequestHeader("X-User-Id") UUID currentUserId,
      @RequestHeader(value = "X-System-Admin", defaultValue = "false") boolean systemAdmin) {
    workspaceService.deleteWorkspace(workspaceId, currentUserId, systemAdmin);
    return ResponseEntity.noContent().build();
  }
}
