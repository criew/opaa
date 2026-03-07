package io.opaa.workspace;

import io.opaa.api.dto.WorkspaceListResponse;
import io.opaa.api.dto.WorkspaceMemberRequest;
import io.opaa.api.dto.WorkspaceMemberResponse;
import io.opaa.api.dto.WorkspaceRequest;
import io.opaa.api.dto.WorkspaceResponse;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Profile("!mock")
@Service
@Transactional(readOnly = true)
public class WorkspaceService {

  private final WorkspaceRepository workspaceRepository;
  private final WorkspaceMembershipRepository workspaceMembershipRepository;

  public WorkspaceService(
      WorkspaceRepository workspaceRepository,
      WorkspaceMembershipRepository workspaceMembershipRepository) {
    this.workspaceRepository = workspaceRepository;
    this.workspaceMembershipRepository = workspaceMembershipRepository;
  }

  @Transactional
  public WorkspaceResponse createWorkspace(
      WorkspaceRequest request, UUID currentUserId, boolean systemAdmin) {
    if (!systemAdmin) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Only system admins can create workspaces");
    }

    UUID ownerId = request.ownerId();
    if (ownerId == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ownerId is required");
    }

    String normalizedName = request.name().trim();
    if (workspaceRepository.existsByNameIgnoreCase(normalizedName)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Workspace name already exists");
    }

    Workspace workspace =
        new Workspace(normalizedName, request.description(), WorkspaceType.SHARED, ownerId);
    appendInitialMemberships(workspace, ownerId, request.initialMembers());

    Workspace saved = workspaceRepository.save(workspace);
    return toWorkspaceResponse(saved, currentUserId);
  }

  public List<WorkspaceListResponse> listWorkspaces(UUID currentUserId) {
    return workspaceRepository
        .findDistinctByMembershipsUserIdWithMemberships(currentUserId)
        .stream()
        .map(workspace -> toWorkspaceListResponse(workspace, currentUserId))
        .toList();
  }

  public WorkspaceResponse getWorkspace(UUID workspaceId, UUID currentUserId, boolean systemAdmin) {
    Workspace workspace =
        workspaceRepository
            .findByIdWithMemberships(workspaceId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found"));

    if (!systemAdmin && userMembership(workspace, currentUserId) == null) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "You are not a member of this workspace");
    }

    return toWorkspaceResponse(workspace, currentUserId);
  }

  @Transactional
  public WorkspaceResponse updateWorkspace(
      UUID workspaceId, WorkspaceRequest request, UUID currentUserId) {
    Workspace workspace =
        workspaceRepository
            .findByIdWithMemberships(workspaceId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found"));

    WorkspaceMembership membership = userMembership(workspace, currentUserId);
    if (membership == null
        || (membership.getRole() != WorkspaceRole.ADMIN
            && membership.getRole() != WorkspaceRole.OWNER)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Only admins or owners can update a workspace");
    }

    String normalizedName = request.name().trim();
    if (!workspace.getName().equalsIgnoreCase(normalizedName)
        && workspaceRepository.existsByNameIgnoreCase(normalizedName)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Workspace name already exists");
    }

    workspace.updateDetails(normalizedName, request.description());
    Workspace updated = workspaceRepository.save(workspace);
    return toWorkspaceResponse(updated, currentUserId);
  }

  @Transactional
  public void deleteWorkspace(UUID workspaceId, UUID currentUserId, boolean systemAdmin) {
    Workspace workspace =
        workspaceRepository
            .findByIdWithMemberships(workspaceId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found"));

    if (workspace.isPersonal()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Personal workspaces cannot be deleted");
    }

    WorkspaceMembership membership = userMembership(workspace, currentUserId);
    boolean owner = membership != null && membership.getRole() == WorkspaceRole.OWNER;
    if (!systemAdmin && !owner) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Only the owner or system admin can delete a workspace");
    }

    workspaceRepository.delete(workspace);
  }

  private void appendInitialMemberships(
      Workspace workspace, UUID ownerId, List<WorkspaceMemberRequest> initialMembers) {
    Map<UUID, WorkspaceRole> resolvedRoles = new java.util.LinkedHashMap<>();
    if (initialMembers != null) {
      for (WorkspaceMemberRequest member : initialMembers) {
        if (member == null) {
          continue;
        }
        resolvedRoles.put(member.userId(), member.role());
      }
    }
    resolvedRoles.put(ownerId, WorkspaceRole.OWNER);
    resolvedRoles.forEach(
        (userId, role) -> workspace.addMembership(new WorkspaceMembership(userId, role)));
  }

  private WorkspaceMembership userMembership(Workspace workspace, UUID userId) {
    return workspace.getMemberships().stream()
        .filter(membership -> membership.getUserId().equals(userId))
        .findFirst()
        .orElse(null);
  }

  private WorkspaceListResponse toWorkspaceListResponse(Workspace workspace, UUID currentUserId) {
    WorkspaceMembership membership = userMembership(workspace, currentUserId);
    return new WorkspaceListResponse(
        workspace.getId(),
        workspace.getName(),
        workspace.getDescription(),
        workspace.getType(),
        workspace.getMemberships().size(),
        membership == null ? null : membership.getRole(),
        workspace.getCreatedAt(),
        workspace.getUpdatedAt());
  }

  private WorkspaceResponse toWorkspaceResponse(Workspace workspace, UUID currentUserId) {
    WorkspaceMembership membership = userMembership(workspace, currentUserId);
    Map<WorkspaceRole, Long> roleCounts = new EnumMap<>(WorkspaceRole.class);
    for (WorkspaceRole role : WorkspaceRole.values()) {
      roleCounts.put(role, 0L);
    }
    workspace.getMemberships().forEach(m -> roleCounts.merge(m.getRole(), 1L, Long::sum));

    List<WorkspaceMemberResponse> members =
        workspaceMembershipRepository.findByWorkspaceId(workspace.getId()).stream()
            .map(m -> new WorkspaceMemberResponse(m.getUserId(), m.getRole(), m.getCreatedAt()))
            .toList();

    return new WorkspaceResponse(
        workspace.getId(),
        workspace.getName(),
        workspace.getDescription(),
        workspace.getType(),
        workspace.getOwnerId(),
        members.size(),
        membership == null ? null : membership.getRole(),
        roleCounts,
        members,
        workspace.getCreatedAt(),
        workspace.getUpdatedAt());
  }
}
