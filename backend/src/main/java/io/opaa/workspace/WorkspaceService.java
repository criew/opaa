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

  public WorkspaceService(WorkspaceRepository workspaceRepository) {
    this.workspaceRepository = workspaceRepository;
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

  public List<WorkspaceMemberResponse> listMembers(UUID workspaceId, UUID currentUserId) {
    Workspace workspace = loadWorkspace(workspaceId);
    requireMembership(workspace, currentUserId);

    return workspace.getMemberships().stream()
        .map(m -> new WorkspaceMemberResponse(m.getUserId(), m.getRole(), m.getCreatedAt()))
        .toList();
  }

  @Transactional
  public WorkspaceMemberResponse addMember(
      UUID workspaceId, UUID memberUserId, WorkspaceRole requestedRole, UUID currentUserId) {
    Workspace workspace = loadWorkspace(workspaceId);
    WorkspaceMembership actor = requireManager(workspace, currentUserId);
    rejectPersonalWorkspaceMemberChanges(workspace);

    if (userMembership(workspace, memberUserId) != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "User is already a workspace member");
    }

    WorkspaceRole roleToAssign = requestedRole == null ? WorkspaceRole.VIEWER : requestedRole;
    if (roleToAssign == WorkspaceRole.OWNER) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Use transfer ownership endpoint to assign OWNER role");
    }
    ensureCanManageRole(actor.getRole(), roleToAssign);

    WorkspaceMembership membership = new WorkspaceMembership(memberUserId, roleToAssign);
    workspace.addMembership(membership);
    workspaceRepository.save(workspace);

    return new WorkspaceMemberResponse(
        membership.getUserId(), membership.getRole(), membership.getCreatedAt());
  }

  @Transactional
  public WorkspaceMemberResponse updateMemberRole(
      UUID workspaceId, UUID memberUserId, WorkspaceRole newRole, UUID currentUserId) {
    Workspace workspace = loadWorkspace(workspaceId);
    WorkspaceMembership actor = requireManager(workspace, currentUserId);
    if (newRole == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role is required");
    }

    WorkspaceMembership target = userMembership(workspace, memberUserId);
    if (target == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace member not found");
    }
    if (target.getRole() == WorkspaceRole.OWNER) {
      if (actor.getRole() != WorkspaceRole.OWNER) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "Only owners can manage owner role assignments");
      }
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Owner role changes require ownership transfer");
    }
    if (newRole == WorkspaceRole.OWNER) {
      if (actor.getRole() != WorkspaceRole.OWNER) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "Only owners can promote members to OWNER");
      }
      actor.setRole(WorkspaceRole.ADMIN);
      target.setRole(WorkspaceRole.OWNER);
      workspaceRepository.save(workspace);
      return new WorkspaceMemberResponse(
          target.getUserId(), target.getRole(), target.getCreatedAt());
    }

    ensureCanManageRole(actor.getRole(), target.getRole());
    ensureCanManageRole(actor.getRole(), newRole);
    target.setRole(newRole);
    workspaceRepository.save(workspace);
    return new WorkspaceMemberResponse(target.getUserId(), target.getRole(), target.getCreatedAt());
  }

  @Transactional
  public void removeMember(UUID workspaceId, UUID memberUserId, UUID currentUserId) {
    Workspace workspace = loadWorkspace(workspaceId);
    WorkspaceMembership actor = requireManager(workspace, currentUserId);

    WorkspaceMembership target = userMembership(workspace, memberUserId);
    if (target == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace member not found");
    }
    if (target.getRole() == WorkspaceRole.OWNER) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Workspace owner cannot be removed");
    }

    ensureCanManageRole(actor.getRole(), target.getRole());
    workspace.removeMembership(target);
    workspaceRepository.save(workspace);
  }

  @Transactional
  public void transferOwnership(UUID workspaceId, UUID newOwnerUserId, UUID currentUserId) {
    Workspace workspace = loadWorkspace(workspaceId);
    WorkspaceMembership actor = userMembership(workspace, currentUserId);
    if (actor == null || actor.getRole() != WorkspaceRole.OWNER) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Only workspace owners can transfer ownership");
    }

    WorkspaceMembership newOwnerMembership = userMembership(workspace, newOwnerUserId);
    if (newOwnerMembership == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Target user is not a workspace member");
    }
    if (newOwnerMembership.getRole() == WorkspaceRole.OWNER) {
      return;
    }

    actor.setRole(WorkspaceRole.ADMIN);
    newOwnerMembership.setRole(WorkspaceRole.OWNER);
    workspaceRepository.save(workspace);
  }

  @Transactional
  public WorkspaceResponse updateWorkspace(
      UUID workspaceId, WorkspaceRequest request, UUID currentUserId, boolean systemAdmin) {
    Workspace workspace =
        workspaceRepository
            .findByIdWithMemberships(workspaceId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found"));

    WorkspaceMembership membership = userMembership(workspace, currentUserId);
    boolean adminOrOwner =
        membership != null
            && (membership.getRole() == WorkspaceRole.ADMIN
                || membership.getRole() == WorkspaceRole.OWNER);
    if (!systemAdmin && !adminOrOwner) {
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
    Workspace workspace = loadWorkspace(workspaceId);

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

  private Workspace loadWorkspace(UUID workspaceId) {
    return workspaceRepository
        .findByIdWithMemberships(workspaceId)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found"));
  }

  private WorkspaceMembership requireMembership(Workspace workspace, UUID userId) {
    WorkspaceMembership membership = userMembership(workspace, userId);
    if (membership == null) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "You are not a member of this workspace");
    }
    return membership;
  }

  private WorkspaceMembership requireManager(Workspace workspace, UUID userId) {
    WorkspaceMembership membership = requireMembership(workspace, userId);
    if (membership.getRole() != WorkspaceRole.ADMIN
        && membership.getRole() != WorkspaceRole.OWNER) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Only admins or owners can manage workspace members");
    }
    return membership;
  }

  private void rejectPersonalWorkspaceMemberChanges(Workspace workspace) {
    if (workspace.isPersonal()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Members cannot be added to personal workspaces");
    }
  }

  private void ensureCanManageRole(WorkspaceRole actorRole, WorkspaceRole targetRole) {
    if (actorRole == WorkspaceRole.OWNER) {
      return;
    }
    if (actorRole == WorkspaceRole.ADMIN
        && roleRank(targetRole) <= roleRank(WorkspaceRole.EDITOR)) {
      return;
    }
    throw new ResponseStatusException(
        HttpStatus.FORBIDDEN, "Insufficient role permissions for this member change");
  }

  private int roleRank(WorkspaceRole role) {
    return switch (role) {
      case VIEWER -> 1;
      case EDITOR -> 2;
      case ADMIN -> 3;
      case OWNER -> 4;
    };
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
        workspace.getMemberships().stream()
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
