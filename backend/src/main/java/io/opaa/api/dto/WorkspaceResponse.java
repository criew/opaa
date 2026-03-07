package io.opaa.api.dto;

import io.opaa.workspace.WorkspaceRole;
import io.opaa.workspace.WorkspaceType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record WorkspaceResponse(
    UUID id,
    String name,
    String description,
    WorkspaceType type,
    UUID ownerId,
    int memberCount,
    WorkspaceRole userRole,
    Map<WorkspaceRole, Long> roleCounts,
    List<WorkspaceMemberResponse> members,
    Instant createdAt,
    Instant updatedAt) {}
